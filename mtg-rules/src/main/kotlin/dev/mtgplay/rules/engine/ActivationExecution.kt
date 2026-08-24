package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.PaymentPlan

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
    val paid = payAbilityCost(cleared, source, ability, plan, pending.chosenDiscard.orEmpty())
    val onStack =
        paid
            .updateStack { it.adding(entry) }
            .emit(GameEvent.AbilityActivated(pending.activator, source.id, source.card))
    // CR 602.2c / CR 117.3c: the activator keeps priority; pass-flags reset (an action was taken).
    return priorityTo(clearPriorityRound(onStack), pending.activator)
}

/**
 * The CR 601.2c re-validation of an activation's targets (reached through CR 602.2b): the gathered
 * choices satisfy the spec and are legal right now. They were enumerated legally and nothing can have
 * changed while gathering — the whole activation is one transition — so a violation is an engine defect
 * and fails loudly (ADR-005). Emits nothing itself; the mirror of the cast pipeline's `establishTargets`.
 */
private fun establishActivationTargets(
    state: GameState,
    entry: StackEntry.ActivatedAbilityOnStack,
) {
    val spec = entry.ability.targetSpec
    if (spec == TargetSpec.None) {
        require(entry.targets.isEmpty()) {
            "CR 601.2c: ${entry.sourceCard.name}'s ability targets nothing but ${entry.targets} were chosen"
        }
        return
    }
    require(entry.targets.size == 1) {
        "CR 601.2c: ${entry.sourceCard.name}'s ability demands exactly one target, got ${entry.targets}"
    }
    entry.targets.forEach { target ->
        require(isTargetLegal(state, spec, target, entry.controller, self = null)) {
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
    if (!allTargetsIllegal(state, entry.ability.targetSpec, entry.targets, entry.controller, self = null)) {
        return null
    }
    val removed = state.updateStack { it.removingAt(it.lastIndex) }
    return grantPriorityRound(
        removed.emit(GameEvent.AbilityFizzled(entry.controller, entry.sourceCard, triggered = false)),
    )
}

/**
 * Pays every component of [ability]'s cost in order (CR 602.2b): mana, tap/sacrifice/discard self, and a
 * chosen discard. The payer is the source's controller — ownership in the MVP pool — so no separate
 * activator parameter is needed. [chosenDiscard] is the card(s) chosen for a "discard a card" component.
 */
private fun payAbilityCost(
    state: GameState,
    source: GameObject,
    ability: ActivatedAbility,
    plan: PaymentPlan,
    chosenDiscard: List<ObjectId>,
): GameState {
    val payer = source.owner
    return ability.cost.fold(state) { current, component ->
        when (component) {
            is AbilityCost.Mana -> payManaPlan(current, payer, component.cost, plan)
            AbilityCost.TapSelf -> tapObjectForCost(current, source.id)
            AbilityCost.SacrificeSelf -> sacrificePermanents(current, payer, listOf(source.id))
            AbilityCost.DiscardSelf -> discardApplyingReplacements(current, payer, source.id)
            AbilityCost.DiscardACard ->
                chosenDiscard.fold(current) { s, id -> discardApplyingReplacements(s, payer, id) }
        }
    }
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
    return state
        .copy(
            sharedZones =
                state.sharedZones.copy(
                    battlefield = battlefield.removingAt(index).addingAt(index, source.copy(tapped = true)),
                ),
        ).emit(GameEvent.ObjectTapped(id, source.card))
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
    // CR 701.18: an ability whose effect is a library search (Ash Barrens' landcycling) is orchestrated —
    // it may pause for the find-one choice and shuffles through the match PRNG — rather than run as a pure effect.
    val early =
        fizzleActivatedAbility(state, entry)
            ?: entry.ability.librarySearch?.let { orchestrateLibrarySearch(state, entry, it) }
    if (early != null) return early
    val context = ResolutionContext(entry.controller, entry.targets, source = entry.sourceId)
    val resolved = entry.ability.effect.resolve(state, context)
    require(resolved.sharedZones.stack == state.sharedZones.stack) {
        "CR 113.7a: an activated ability's effect performs its instructions but does not move the ability " +
            "off the stack — that cessation is the engine's move"
    }
    // CR 608.2c: a post-resolution clause the ability carries runs after its ordinary effect and may pause
    // (`FW-CLAUSEHOOK`). With no clause this is the bare CR 113.7a cessation.
    return orchestrateResolutionClauses(resolved, entry)
}
