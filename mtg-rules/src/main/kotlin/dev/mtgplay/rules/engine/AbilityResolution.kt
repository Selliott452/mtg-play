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
    val early = fizzleTrigger(state, entry) ?: resolveOrchestratedTrigger(state, entry)
    if (early != null) return early
    val context =
        ResolutionContext(
            controller = trigger.controller,
            targets = entry.targets,
            amount = trigger.amount,
            subject = trigger.subject,
        )
    val resolved = trigger.ability.effect.resolve(state, context)
    require(resolved.sharedZones.stack == state.sharedZones.stack) {
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
    if (!allTargetsIllegal(state, trigger.ability.targetSpec, entry.targets, trigger.controller)) return null
    val removed = state.updateStack { it.removingAt(it.lastIndex) }
    return grantPriorityRound(
        removed.emit(GameEvent.AbilityFizzled(trigger.controller, trigger.sourceCard, triggered = true)),
    )
}

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
        entry.trigger.ability.optionalDiscardDraw != null -> resolveOptionalDiscardDrawTrigger(state, entry)
        else -> null
    }
