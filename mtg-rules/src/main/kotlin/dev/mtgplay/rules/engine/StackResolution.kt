package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingColorChoice
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.AdvanceResult

/**
 * Resolves the topmost object of the stack (CR 608.1) — reached when all players pass in
 * succession with a nonempty stack (CR 117.4). Afterwards the active player receives priority
 * (CR 117.3b) in a fresh round: pass-flags reset because a resolution is game action, so the
 * "all pass in succession" count starts over.
 */
internal fun resolveTopOfStack(state: GameState): AdvanceResult {
    val top = state.sharedZones.stack.lastOrNull() ?: error("CR 608.1: resolution requires a nonempty stack")
    return when (top) {
        is StackEntry.Spell -> resolveSpell(state, top)
        is StackEntry.Ability -> resolveAbility(state, top)
        is StackEntry.ActivatedAbilityOnStack -> resolveActivatedAbility(state, top)
    }
}

/**
 * Resolves the spell [entry] (CR 608.2):
 * 1. **Target re-check** (CR 608.2b): if the spell targets and *every* target is now illegal,
 *    the spell does not resolve — none of its instructions are performed ("fizzles"), and it goes
 *    to its owner's graveyard without ever becoming a permanent.
 * 2. Otherwise it resolves per its card type:
 *    - an **instant or sorcery** performs its [dev.mtgplay.core.definition.ResolutionEffect]
 *      instructions (CR 608.2c) while still on the stack, then its card is put into its owner's
 *      graveyard as a new object (CR 608.2m, CR 400.7);
 *    - a **permanent spell** (a creature, in the P3.2 pool) has no CR 608.2c effect of its own and
 *      instead becomes a permanent on the battlefield (CR 608.3) — see
 *      [putResolvedSpellOntoBattlefield].
 *   Either move is the engine's, never the effect's.
 *
 * A spell with *some* legal targets still resolves (CR 608.2b), performing what it can — a
 * distinction with no observable case until multi-target spells exist, since every spell in the
 * pool has at most one target.
 */
private fun resolveSpell(
    state: GameState,
    entry: StackEntry.Spell,
): AdvanceResult {
    // CR 608.2b precedes everything: a spell that does not resolve performs nothing at all. Then
    // CR 118.3a's "unless its controller pays" is engine-orchestrated rather than a resolution effect,
    // because the payment is a decision (ADR-004) — and because it comes *second*, a counter whose
    // target has already gone fizzles and nobody is ever asked to pay.
    val early =
        fizzleSpell(state, entry)
            ?: entry.definition.counterUnlessPaid?.let { orchestrateCounterUnlessPaid(state, entry, it) }
    if (early != null) return early
    return if (isPermanentSpell(entry)) {
        // CR 614.12: a permanent that chooses a colour as it enters (Utopia Sprawl) pauses here for the
        // choice before it enters; the spell stays on top of the stack until the colour arrives.
        if (entry.definition.choosesColorAsItEnters && state.pendingColorChoice == null) {
            val paused = state.copy(pendingColorChoice = PendingColorChoice(entry.controller))
            AdvanceResult.NeedsDecision(paused, pendingColorChoiceRequest(paused))
        } else {
            enterResolvedPermanent(state, entry, chosenColor = null)
        }
    } else {
        val resolved =
            entry.definition.resolution.resolve(
                state,
                ResolutionContext(
                    controller = entry.controller,
                    targets = entry.targets,
                    discardedForCost = entry.discardedForCost,
                    source = entry.obj.id,
                    sacrificedForCost = entry.sacrificedForCost,
                ),
            )
        // Relaxed by `FW-COUNTER` from "the stack is unchanged" to what that assertion actually meant.
        // A counter's whole job is to modify the stack (CR 701.5a), so the old form was false for every
        // counter; what must still hold is that the effect did not move the **resolving** spell, whose
        // CR 608.2m departure is the engine's move alone — and CR 608.1 says that spell is topmost.
        require((resolved.sharedZones.stack.lastOrNull() as? StackEntry.Spell)?.obj?.id == entry.obj.id) {
            "CR 608.2m: a resolution effect must not move the resolving spell off the top of the stack — " +
                "that is the engine's move"
        }
        // A post-resolution clause may run last and pause for a mid-resolution decision; otherwise the spell
        // simply leaves the stack now. The dispatch is the shared `FW-CLAUSEHOOK` hook (ResolutionClauseHook.kt),
        // which a resolving ability reaches through the same call — the clauses are carried by
        // [dev.mtgplay.core.definition.ResolutionClauses], not by a spell definition.
        orchestrateResolutionClauses(resolved, entry)
    }
}

/**
 * The CR 608.2b removal of a spell whose every target is now illegal, or  when it resolves
 * normally. Unlike an ability's fizzle, a card moves: the spell is put into its owner's graveyard and
 * never enters the battlefield, whatever its card type would have become — unless a flashback
 * leave-stack replacement exiles it instead (CR 702.34e).
 */
private fun fizzleSpell(
    state: GameState,
    entry: StackEntry.Spell,
): AdvanceResult? {
    val spec = entry.definition.targetSpec
    if (!allTargetsIllegal(state, spec, entry.targets, entry.controller, entry.obj.id)) return null
    val left = putResolvedSpellOffStack(state, entry)
    val fizzled =
        left.state.emit(
            GameEvent.SpellFizzled(entry.controller, entry.obj.id, entry.obj.card, left.newObjectId),
        )
    return grantPriorityRound(narrateLeaveStackExile(fizzled, entry, left))
}

/**
 * Finishes an instant or sorcery resolution (CR 608.2m): puts the spell's card off the stack — to its
 * owner's graveyard, or to exile for a flashback spell (CR 702.34e) — narrates it, and grants a fresh
 * priority round. Shared by the ordinary path and the resume after a library-reveal selection.
 */
internal fun completeInstantSorceryResolution(
    state: GameState,
    entry: StackEntry.Spell,
): AdvanceResult {
    val left = putResolvedSpellOffStack(state, entry)
    val narrated =
        left.state.emit(
            GameEvent.SpellResolved(entry.controller, entry.obj.id, entry.obj.card, left.newObjectId),
        )
    return grantPriorityRound(narrateLeaveStackExile(narrated, entry, left))
}

/**
 * Whether [entry] is a permanent spell (CR 608.3): every card type in the MVP pool is either an
 * instant or a sorcery (which resolve into the graveyard) or a permanent type (which resolves onto
 * the battlefield). Lands are the one permanent type that is *played*, not cast (CR 305.1), so a
 * land spell never reaches resolution; the only permanent spells in the P3.2 pool are creatures.
 */
private fun isPermanentSpell(entry: StackEntry.Spell): Boolean {
    val types = entry.definition.characteristics.cardTypes
    return CardType.INSTANT !in types && CardType.SORCERY !in types
}

/**
 * The CR 608.3 move for a permanent spell: the resolving spell leaves the stack and enters the
 * battlefield under its controller's control as a **new** object (CR 400.7) — summoning sick
 * (CR 302.6), untapped unless the card's own CR 614.1c "enters tapped" self-replacement says
 * otherwise ([dev.mtgplay.core.definition.CardDefinition.entersTapped]), and with no marked damage
 * (the [GameObject] defaults). An Aura enters
 * **attached** to the object it targeted while on the stack (CR 303.4f, CR 601.2c); every other
 * permanent spell attaches to nothing. Controller is owner in the MVP pool (control-changing effects
 * are Phase 4). The vanilla-and-keyword-only creatures and the Auras of the pool have no
 * enters-the-battlefield effect (Phase 5), so entering the battlefield is the whole of resolution.
 */
internal fun putResolvedSpellOntoBattlefield(
    state: GameState,
    entry: StackEntry.Spell,
    chosenColor: dev.mtgplay.core.mana.Color?,
): Pair<GameState, ObjectId> {
    val stack = state.sharedZones.stack
    check(stack.lastOrNull() == entry) { "CR 608.1: only the topmost stack object may resolve" }
    val (id, allocated) = state.allocateObjectId()
    val permanent =
        GameObject(
            id = id,
            card = entry.obj.card,
            owner = entry.obj.owner,
            attachedTo = auraAttachmentTargetOf(entry),
            // CR 614.1c: a permanent whose card says it enters tapped does so; the replacement modifies
            // the entering event, so this is not a subsequent tap.
            tapped = entry.definition.entersTapped,
            // CR 614.12: the colour chosen as this object entered (Utopia Sprawl), or null.
            chosenColor = chosenColor,
        )
    val moved =
        allocated
            .updateStack { it.removingAt(it.lastIndex) }
            .updateBattlefield { it.adding(permanent) }
    return moved to id
}

/**
 * The object an Aura enters attached to (CR 303.4f): the permanent it targeted while on the stack
 * (CR 601.2c). A permanent spell that is not an Aura (no [TargetSpec.Enchantable]) attaches to
 * nothing. Fails loudly if an Aura's settled target is not a permanent — the CR 608.2b re-check has
 * already run, so reaching here with a gone/wrong target is an engine defect (ADR-005).
 */
private fun auraAttachmentTargetOf(entry: StackEntry.Spell): ObjectId? =
    when (entry.definition.targetSpec) {
        TargetSpec.None,
        TargetSpec.AnyTarget,
        TargetSpec.TargetPlayer,
        TargetSpec.TargetOpponent,
        is TargetSpec.TargetPermanent,
        is TargetSpec.SpellOnStack,
        -> null
        is TargetSpec.Enchantable ->
            (entry.targets.singleOrNull() as? Target.Permanent)?.id
                ?: error("CR 303.4f: an Aura must enter attached to its permanent target, got ${entry.targets}")
    }

/**
 * The CR 608.2m move for an instant or sorcery leaving the stack **on resolution or a fizzle**: the
 * topmost-ness assertion CR 608.1 licenses here, plus the shared, position-agnostic move
 * ([putSpellOffStack]) that a counter (CR 701.5a) reaches from anywhere on the stack.
 */
private fun putResolvedSpellOffStack(
    state: GameState,
    entry: StackEntry.Spell,
): SpellLeftStack {
    check(state.sharedZones.stack.lastOrNull() == entry) { "CR 608.1: only the topmost stack object may resolve" }
    return putSpellOffStack(state, entry)
}
