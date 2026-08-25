package dev.mtgplay.rules.engine

import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingOptionalTrigger
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId

/*
 * The "you may" that wraps a whole triggered ability (CR 603.2, CR 601.3b) — Mortuary Mire's "When this
 * land enters, you may put target creature card from your graveyard on top of your library". Additive
 * (`W8-A`).
 *
 * The sibling of the bare optional-draw clause (OptionalDraw.kt) and deliberately not a member of it:
 * that one is a [dev.mtgplay.core.definition.ResolutionClauses] member with its own draw count and runs
 * *after* the resolving object's ordinary effect, whereas this one gates that effect entirely and so has
 * to sit ahead of it. What they share is the request — a plain [DecisionRequest.ChooseYesNo], routed by
 * which `pending*` record is open, the idiom six flows already share.
 *
 * **Why the yes/no is a real decision and not a formality.** Mortuary Mire's acceptance replaces the
 * controller's next draw with a creature card they already have; declining keeps an unknown card, which
 * is better whenever the graveyard's best creature is worse than an average draw, and is the whole
 * reason the card prints "may" rather than a mandatory instruction. Making it mandatory would delete
 * that from the action space an agent trains against (ADR-005).
 */

/**
 * Pauses a resolving "you may" triggered ability for its controller's yes/no (CR 603.2). Called from
 * [resolveAbility] **after** the CR 608.2b target re-check and the CR 603.4 intervening-if check, so the
 * question is only ever put about an ability that is actually going to resolve.
 *
 * The ability stays on top of the stack (CR 608.1) throughout, which is what makes the resume a pure
 * derivation of the state (ADR-004).
 */
internal fun orchestrateOptionalTrigger(
    state: GameState,
    entry: StackEntry.Ability,
): AdvanceResult {
    val trigger = entry.trigger
    val paused =
        state.copy(
            pendingOptionalTrigger =
                PendingOptionalTrigger(
                    decider = trigger.controller,
                    // CR 113.7c: the source as last known — it may have left the battlefield since it fired.
                    sourceId = trigger.sourceId,
                    sourceCard = trigger.sourceCard,
                ),
        )
    return AdvanceResult.NeedsDecision(paused, pendingOptionalTriggerRequest(paused))
}

/** The yes/no an open "you may" triggered ability is waiting on (CR 603.2). A pure function of the state. */
internal fun pendingOptionalTriggerRequest(state: GameState): DecisionRequest.ChooseYesNo {
    val pending = state.pendingOptionalTrigger ?: error("no optional triggered ability is pending")
    return DecisionRequest.ChooseYesNo(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        prompt = "resolve ${pending.sourceCard.name}'s optional ability",
        cardObjectId = pending.sourceId,
        card = pending.sourceCard,
    )
}

/**
 * Applies the "you may" answer (CR 603.2): [accept] `true` performs the ability's instructions and then
 * whatever post-resolution clause it carries ([performTriggerEffect]); `false` performs nothing at all.
 * Either way the ability ceases to exist (CR 113.7a) — a declined "may" is still a resolved ability, not
 * a fizzled one, so it leaves through the ordinary cessation and narrates as one.
 */
internal fun applyOptionalTriggerYesNo(
    state: GameState,
    accept: Boolean,
): AdvanceResult {
    state.pendingOptionalTrigger ?: error("no optional triggered ability is pending")
    val entry =
        state.sharedZones.stack.lastOrNull() as? StackEntry.Ability
            ?: error("CR 608.1: a 'you may' pause requires its triggered ability on top of the stack")
    val cleared = state.copy(pendingOptionalTrigger = null)
    return if (accept) performTriggerEffect(cleared, entry) else ceaseTriggeredAbility(cleared, entry)
}
