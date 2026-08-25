package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingActivation
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.effect.exileCardFromGraveyard
import dev.mtgplay.rules.effect.returnPermanentToOwnersHand

/*
 * Activated-ability execution and resolution (CR 602.2b–c, CR 608.2): paying the composite cost, putting
 * the ability on the stack ([StackEntry.ActivatedAbilityOnStack]), and resolving it. Split from
 * Activation.kt (enumeration + gathering) so each file stays within its function budget.
 */

/** The source object of a pending activation/option in its [scope] zone, or `null` if it is not there. */
internal fun activationSource(
    state: GameState,
    activator: PlayerId,
    scope: AbilityZoneScope,
    objectId: ObjectId,
): GameObject? =
    when (scope) {
        AbilityZoneScope.Battlefield ->
            state.sharedZones.battlefield.firstOrNull { it.id == objectId && it.owner == activator }
        AbilityZoneScope.Hand -> state.player(activator).hand.firstOrNull { it.id == objectId }
        // CR 113.6b: a graveyard-functioning ability's source is a card in the activator's graveyard.
        AbilityZoneScope.Graveyard -> state.player(activator).graveyard.firstOrNull { it.id == objectId }
    }

/** The [abilityIndex]th activated ability of [sourceObjectId] in its [scope] zone; fails loudly if absent. */
internal fun abilityAt(
    state: GameState,
    activator: PlayerId,
    sourceObjectId: ObjectId,
    scope: AbilityZoneScope,
    abilityIndex: Int,
): ActivatedAbility {
    val source =
        activationSource(state, activator, scope, sourceObjectId)
            ?: error("CR 602.2: the activation source $sourceObjectId is not in its zone $scope")
    val abilities = state.definitions[source.card]?.activatedAbilities.orEmpty()
    return abilities.getOrNull(abilityIndex)
        ?: error("CR 602: ${source.card.name} has no activated ability at index $abilityIndex")
}

/**
 * Executes the activation with the chosen [plan] (CR 602.2b–c): re-validates the chosen targets
 * (CR 601.2c), pays the whole composite cost, puts the ability on the stack as a
 * [StackEntry.ActivatedAbilityOnStack] (its source captured as last-known information, so a
 * self-sacrifice cost does not orphan it), and returns priority to the activator in a fresh round.
 */
internal fun executeActivation(
    state: GameState,
    plan: PaymentPlan,
): AdvanceResult {
    val pending = state.pendingActivation ?: error("no activation is gathering costs")
    val source =
        activationSource(state, pending.activator, pending.source, pending.sourceObjectId)
            ?: error("CR 602.2: the activation source ${pending.sourceObjectId} is not in its zone")
    val ability = abilityAt(state, pending.activator, pending.sourceObjectId, pending.source, pending.abilityIndex)
    val targets =
        pending.chosenTargets
            ?: error("CR 601.2c: an activation's targets must be settled before its cost is paid")
    // Capture the source's last-known information before any cost removes it (self-sacrifice, discard-self).
    val entry =
        StackEntry.ActivatedAbilityOnStack(
            sourceId = source.id,
            sourceCard = source.card,
            controller = pending.activator,
            ability = ability,
            targets = targets,
        )
    val cleared = state.copy(pendingActivation = null)
    establishActivationTargets(cleared, entry)
    // CR 602.5b: the "activate only once each turn" record is made *before* the cost is paid, because a
    // cost that returns or sacrifices the source would otherwise leave nothing to record it on.
    val marked = markAbilityOncePerTurn(cleared, source, ability, pending.abilityIndex)
    val paid = payAbilityCost(marked, source, ability, plan, pending)
    val onStack =
        paid
            .updateStack { it.adding(entry) }
            .emit(GameEvent.AbilityActivated(pending.activator, source.id, source.card))
    // CR 602.2c / CR 117.3c: the activator keeps priority; pass-flags reset (an action was taken).
    return priorityTo(clearPriorityRound(onStack), pending.activator)
}

/**
 * The CR 601.2c re-validation of an activation's targets (reached through CR 602.2b): the gathered
 * choices satisfy the spec's arity and CR 601.2c's same-object rule ([requireWellFormedTargetChoice])
 * and are legal right now. They were enumerated legally and nothing can have changed while gathering —
 * the whole activation is one transition — so a violation is an engine defect and fails loudly
 * (ADR-005). Emits nothing itself; the mirror of the cast pipeline's `establishTargets`, and since
 * `FW-MULTITGT` the two share their arity and distinctness checks rather than restating them.
 */
private fun establishActivationTargets(
    state: GameState,
    entry: StackEntry.ActivatedAbilityOnStack,
) {
    val spec = entry.ability.targetSpec
    // CR 113.7a: an ability on the stack has no residence id, so it excludes nothing from its own
    // enumeration; CR 113.7b/c: its *source* is what protection reads (CR 702.16b), by the last known
    // information the entry captured — the same [Chooser.Ability] the choice was enumerated with, which
    // is what makes this re-validation ask the identical question.
    // CR 601.2c: announceable, not merely legal — the same set the gathering offered, requirements and
    // all (`W8-G`). The CR 608.2b re-check below stays on `legalTargets`, where legality is the question.
    val options = announceableTargets(state, spec, entry.controller, Chooser.Ability(entry.sourceCard))
    requireWellFormedTargetChoice(spec, entry.targets, options.size, "${entry.sourceCard.name}'s ability")
    entry.targets.forEach { target ->
        require(target in options) {
            "CR 601.2c: $target is not a legal target for ${entry.sourceCard.name}'s ability"
        }
    }
}

/**
 * The CR 608.2b removal of an activated ability whose every target is now illegal, or `null` when it
 * resolves normally. **No card moves** — an ability is not a card (CR 113.7a) — so this is a bare stack
 * removal plus its event, deliberately *not* the spell path's graveyard/exile move
 * (docs/design/targeted-abilities.md §6).
 */
private fun fizzleActivatedAbility(
    state: GameState,
    entry: StackEntry.ActivatedAbilityOnStack,
): AdvanceResult? {
    // CR 113.7a: an ability on the stack is not a card and has no residence id, so it excludes nothing.
    // CR 113.7c: its source's characteristics are last known information — which is exactly the case
    // here, since an ability whose cost sacrificed its own source is still on the stack.
    val chooser = Chooser.Ability(entry.sourceCard)
    if (!allTargetsIllegal(state, entry.ability.targetSpec, entry.targets, entry.controller, chooser)) {
        return null
    }
    val removed = state.updateStack { it.removingAt(it.lastIndex) }
    return grantPriorityRound(
        removed.emit(GameEvent.AbilityFizzled(entry.controller, entry.sourceCard, triggered = false)),
    )
}

/**
 * Pays every component of [ability]'s cost in order (CR 602.2b): mana, tap/sacrifice/discard self, and a
 * chosen discard, and a chosen sacrifice. The payer is the source's controller — ownership in the MVP
 * pool — so no separate activator parameter is needed. [pending] supplies the objects chosen while
 * gathering: its `chosenDiscard` for a "discard a card" component and its `chosenSacrifice` for an
 * [AbilityCost.Sacrifice] component.
 *
 * **Components are paid in printed order, so the mana precedes the sacrifice** on every ability in the
 * pool ("{1}, Sacrifice an artifact or creature: …"). That ordering is what makes tapping a permanent
 * for mana and then sacrificing it work; the enumeration reserved exactly the chosen permanents that
 * could *not* survive being paid with (docs/design/mana-payment.md §2.2), so nothing reaches this point
 * that the fold cannot carry out.
 */
private fun payAbilityCost(
    state: GameState,
    source: GameObject,
    ability: ActivatedAbility,
    plan: PaymentPlan,
    pending: PendingActivation,
): GameState {
    val payer = source.owner
    val chosenDiscard = pending.chosenDiscard.orEmpty()
    val chosenSacrifice = pending.chosenSacrifice.orEmpty()
    val chosenReturn = pending.chosenReturn.orEmpty()
    return ability.cost.fold(state) { current, component ->
        when (component) {
            is AbilityCost.Mana -> payManaPlan(current, payer, component.cost, plan)
            AbilityCost.TapSelf -> tapObjectForCost(current, source.id)
            AbilityCost.SacrificeSelf -> sacrificePermanents(current, payer, listOf(source.id))
            AbilityCost.DiscardSelf -> discardApplyingReplacements(current, payer, source.id)
            // CR 701.3a: the source card leaves its owner's graveyard for exile as the cost is paid,
            // which is also what makes the ability usable once and only once.
            AbilityCost.ExileSelfFromGraveyard -> exileCardFromGraveyard(current, source.id)
            AbilityCost.DiscardACard ->
                chosenDiscard.fold(current) { s, id -> discardApplyingReplacements(s, payer, id) }
            // CR 701.17: the permanents chosen while gathering, sacrificed to their owner's graveyard.
            is AbilityCost.Sacrifice -> sacrificePermanents(current, payer, chosenSacrifice)
            // CR 701.4a: the permanents chosen while gathering, returned to their owners' hands. The
            // enumeration reserved each of them from the payment plan unconditionally, so none of them
            // can already have been tapped for mana by the fold above.
            is AbilityCost.ReturnPermanentYouControl ->
                chosenReturn.fold(current) { s, id -> returnPermanentToOwnersHand(s, id) }
        }
    }
}

/**
 * Records that [source] has activated its CR 602.5b "Activate only once each turn" ability, so that
 * [activationOptions] stops enumerating it for the rest of the turn. A no-op for every unrestricted
 * ability, which keeps [GameObject.activatedAbilitiesActivatedThisTurn] empty on ordinary boards and
 * their replay fingerprints unchanged.
 *
 * The index recorded is [abilityIndex], the ability's index among the card's printed activated
 * abilities — the same index the enumeration checks and the same one [PendingActivation] carried, so
 * there is no lookup to get wrong (contrast the mana-ability record, which has to *find* the restricted
 * ability because a plan names a production alternative rather than an ability).
 *
 * A **hand**-scoped ability records nothing: the restriction follows the object (CR 602.5b), and a
 * hand-scoped activation's source is a card that is about to change zones and become a different object
 * (CR 400.7). No card in the gauntlet prints the pairing, and marking a hand object would be a record
 * nothing could ever read.
 */
private fun markAbilityOncePerTurn(
    state: GameState,
    source: GameObject,
    ability: ActivatedAbility,
    abilityIndex: Int,
): GameState {
    if (!ability.oncePerTurn || ability.zoneScope != AbilityZoneScope.Battlefield) return state
    val battlefield = state.sharedZones.battlefield
    val index = battlefield.indexOfFirst { it.id == source.id }
    require(index >= 0) {
        "CR 602.5b: ${source.card.name}'s once-each-turn ability is recorded on its source, but " +
            "${source.id} is not on the battlefield"
    }
    val marked =
        battlefield[index].let {
            it.copy(activatedAbilitiesActivatedThisTurn = it.activatedAbilitiesActivatedThisTurn.adding(abilityIndex))
        }
    // In place: battlefield order is the determinism spine (CR 613.7), so a record must not reorder it.
    return state.updateBattlefield { it.removingAt(index).addingAt(index, marked) }
}

/** Taps the battlefield object [id] to pay a `{T}` cost (CR 602.2a); emits [GameEvent.ObjectTapped]. */
private fun tapObjectForCost(
    state: GameState,
    id: ObjectId,
): GameState {
    val battlefield = state.sharedZones.battlefield
    val index = battlefield.indexOfFirst { it.id == id }
    require(index >= 0) { "CR 602.2a: a {T} cost taps a battlefield source, but $id is not there" }
    val source = battlefield[index]
    require(!source.tapped) { "CR 602.2a: a {T} cost requires an untapped source, but $id is tapped" }
    val tapped =
        state
            .copy(
                sharedZones =
                    state.sharedZones.copy(
                        battlefield = battlefield.removingAt(index).addingAt(index, source.copy(tapped = true)),
                    ),
            ).emit(GameEvent.ObjectTapped(id, source.card))
    // CR 603.2/701.20a: paying a {T} cost is a way of becoming tapped like any other — the source was
    // untapped a line above, so this is always a real flip (`W8-C`).
    return announceBecameTapped(tapped, id)
}

/**
 * Resolves an activated ability on the stack (CR 608.2, CR 113.7a):
 * 1. **Target re-check** (CR 608.2b): if the ability targets and *every* target is now illegal, it does
 *    not resolve — none of its instructions are performed — and it is simply removed from the stack.
 *    No card moves, unlike a fizzled spell's CR 608.2m graveyard move: an ability is not a card
 *    (CR 113.7a). The verdict itself is [allTargetsIllegal], shared with the spell and triggered-ability
 *    paths; only the consequence differs (docs/design/targeted-abilities.md §6).
 * 2. Otherwise it performs its effect against a [ResolutionContext] carrying its controller and its
 *    chosen targets, then ceases to exist.
 *
 * Afterwards the active player receives priority (CR 117.3b) in a fresh round.
 */
internal fun resolveActivatedAbility(
    state: GameState,
    entry: StackEntry.ActivatedAbilityOnStack,
): AdvanceResult {
    check(state.sharedZones.stack.lastOrNull() == entry) { "CR 608.1: only the topmost stack object may resolve" }
    // CR 608.2b precedes CR 608.2c: an ability that does not resolve must not begin any orchestration.
    val fizzled = fizzleActivatedAbility(state, entry)
    if (fizzled != null) return fizzled
    val context =
        ResolutionContext(
            entry.controller,
            entry.targets,
            source = entry.sourceId,
            // CR 120.1 + CR 113.7c: the ability's source object as of activation.
            sourceCard = entry.sourceCard,
        )
    val resolved = entry.ability.effect.resolve(state, context)
    require(resolved.sharedZones.stack == state.sharedZones.stack) {
        "CR 113.7a: an activated ability's effect performs its instructions but does not move the ability " +
            "off the stack — that cessation is the engine's move"
    }
    // CR 608.2c: a post-resolution clause the ability carries runs after its ordinary effect and may pause
    // (`FW-CLAUSEHOOK`). With no clause this is the bare CR 113.7a cessation.
    return orchestrateResolutionClauses(resolved, entry)
}
