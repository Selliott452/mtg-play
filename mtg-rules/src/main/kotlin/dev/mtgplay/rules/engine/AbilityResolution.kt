package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.AdvanceResult

/**
 * Resolves a triggered ability on the stack (CR 608.2, CR 113.7a) — reached when all players pass with
 * the ability on top of the stack (CR 117.4). The ability performs its [ResolutionEffect] instructions
 * (CR 608.2c) against a [ResolutionContext] carrying its controller and the trigger's captured linked
 * information ([dev.mtgplay.core.state.PendingTrigger.amount] and `subject`), then **ceases to exist**
 * (CR 113.7a): unlike a spell, no card moves to the graveyard — the ability was never a card. Any zone
 * changes the effect itself makes (a token, a draw, a return-to-hand) are the effect's own.
 *
 * **Target re-check (CR 608.2b).** If the ability targets and *every* target chosen at CR 603.3d is now
 * illegal, it does not resolve: none of its instructions are performed, and it is simply removed from
 * the stack. No card moves — unlike a fizzled spell's CR 608.2m graveyard move, an ability is not a card
 * (CR 113.7a) — so the spell path's `putResolvedSpellOffStack` must **not** be reused here; only the
 * verdict [allTargetsIllegal] is shared (docs/design/targeted-abilities.md §6). A targeting ability that
 * was put on the stack with **no** targets — its controller had no legal choice (CR 603.3d) — is
 * vacuously all-illegal, and correctly does nothing.
 *
 * Afterwards the active player receives priority (CR 117.3b) in a fresh round, exactly as after a spell
 * resolves.
 */
internal fun resolveAbility(
    state: GameState,
    entry: StackEntry.Ability,
): AdvanceResult {
    check(state.sharedZones.stack.lastOrNull() == entry) { "CR 608.1: only the topmost stack object may resolve" }
    val trigger = entry.trigger
    // CR 608.2b precedes CR 608.2c: an ability that does not resolve performs nothing at all, and must
    // not begin an orchestrated "you may" flow it would then have to unwind. Then two triggered "you may"
    // clauses are engine-orchestrated rather than plain effects: madness's reflexive cast (CR 702.35b)
    // and the optional discard-then-draw (CR 601.3b, Melded Moxite).
    val early =
        fizzleTrigger(state, entry)
            ?: interveningIfFailure(state, entry)
            ?: resolveOrchestratedTrigger(state, entry)
    if (early != null) return early
    val context =
        ResolutionContext(
            controller = trigger.controller,
            targets = entry.targets,
            amount = trigger.amount,
            subject = trigger.subject,
            source = trigger.sourceId,
            // CR 120.1 + CR 113.7c: a triggered ability's damage source is its source object, as
            // last-known information — the source may have left the battlefield since it fired.
            sourceCard = trigger.sourceCard,
            // CR 607.2, CR 603.10: the linked exile record the source held when this trigger fired.
            // Read from the trigger and never from the battlefield: a leaves-the-battlefield ability's
            // source is by definition already gone by the time it resolves.
            linkedExiled = trigger.linkedExiled,
        )
    val resolved = trigger.ability.effect.resolve(state, context)
    // Relaxed by `FW-COUNTER` from "the stack is unchanged", which is false for any ability that
    // counters a spell (Spellstutter Sprite's, CR 701.5a). What must still hold is that the resolving
    // *ability* is still the topmost object: its CR 113.7a cessation is the engine's move alone.
    require(resolved.sharedZones.stack.lastOrNull() == entry) {
        "CR 113.7a: a triggered ability's effect performs its instructions but does not move the ability " +
            "off the stack — that cessation is the engine's move"
    }
    // CR 608.2c: a post-resolution clause the ability carries runs after its ordinary effect and may pause
    // (Faerie Seer's enters-the-battlefield scry). With no clause this is the bare CR 113.7a cessation.
    return orchestrateResolutionClauses(resolved, entry)
}

/**
 * The CR 608.2b removal of a triggered ability whose every target is now illegal, or `null` when it
 * resolves normally. **No card moves** — an ability is not a card (CR 113.7a) — so this is a bare stack
 * removal plus its event, deliberately *not* the spell path's graveyard/exile move
 * (docs/design/targeted-abilities.md §6).
 */
private fun fizzleTrigger(
    state: GameState,
    entry: StackEntry.Ability,
): AdvanceResult? {
    val trigger = entry.trigger
    // CR 113.7a: an ability on the stack is not a card and has no residence id, so it excludes nothing.
    // CR 113.7b/c: its source, by last known information, is what CR 702.16b tests a protected target
    // against — the same [Chooser.Ability] `TriggerTargeting.kt` enumerated the CR 603.3d choice with,
    // which is what keeps this re-check from drifting from that choice (ADR-005).
    val chooser = Chooser.Ability(trigger.sourceCard)
    if (!allTargetsIllegal(state, trigger.ability.targetSpec, entry.targets, trigger.controller, chooser)) {
        return null
    }
    return abilityLeftStackDoingNothing(
        state,
        GameEvent.AbilityFizzled(trigger.controller, trigger.sourceCard, triggered = true),
    )
}

/**
 * The CR 603.4 removal of a triggered ability whose intervening-if clause is no longer true, or `null`
 * when it still is — and for every ability that declares none, which is all but Goblin Bushwhacker's.
 *
 * The **second** of the two checks CR 603.4 demands; the first is in [enqueuePendingTrigger], and both
 * ask [interveningIfHolds] so they cannot drift apart (InterveningIfCheck.kt). Ordered beside the
 * CR 608.2b fizzle because the outcome is identical — the ability performs nothing — and, like the
 * fizzle, it must run before any orchestrated "you may" flow it would otherwise have to unwind. It is a
 * distinct *rule* from the fizzle though, and narrates as one.
 */
private fun interveningIfFailure(
    state: GameState,
    entry: StackEntry.Ability,
): AdvanceResult? {
    val trigger = entry.trigger
    if (interveningIfHolds(state, trigger.ability, trigger.sourceId)) return null
    return abilityLeftStackDoingNothing(
        state,
        GameEvent.AbilityConditionFailed(trigger.controller, trigger.sourceCard),
    )
}

/**
 * Removes the topmost (resolving) triggered ability from the stack having performed nothing, narrating
 * it as [narration], and grants a fresh priority round (CR 113.7a — the ability simply ceases to exist).
 *
 * Shared by the CR 608.2b fizzle and the CR 603.4 intervening-if failure: the two rules differ in *why*
 * the ability does nothing and agree exactly on *what happens next*, so the transition is written once
 * and the difference is carried by the event.
 */
private fun abilityLeftStackDoingNothing(
    state: GameState,
    narration: GameEvent,
): AdvanceResult = grantPriorityRound(state.updateStack { it.removingAt(it.lastIndex) }.emit(narration))

/**
 * The engine-orchestrated resolution of a triggered "you may" clause, or `null` for a plain effect: a
 * madness reflexive cast (CR 702.35b) or an optional discard-then-draw (CR 601.3b). Split out so
 * [resolveAbility] has a single early return.
 */
private fun resolveOrchestratedTrigger(
    state: GameState,
    entry: StackEntry.Ability,
): AdvanceResult? =
    when {
        entry.trigger.ability.condition == TriggerCondition.MadnessCast -> resolveMadnessTrigger(state, entry)
        entry.trigger.ability.condition == TriggerCondition.ReboundCast -> resolveReboundTrigger(state, entry)
        entry.trigger.ability.optionalDiscardDraw != null -> resolveOptionalDiscardDrawTrigger(state, entry)
        else -> null
    }
