package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.OptionalDraw
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingOptionalDraw
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.resolutionController
import dev.mtgplay.core.state.resolutionSourceCard
import dev.mtgplay.core.state.resolutionSourceId
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.effect.drawCards

/*
 * The bare optional "you may draw N" clause (CR 601.3b) — Ninja of the Deep Hours' combat-damage trigger.
 * Additive (`FW-OPTDRAW`), and the simplest member of the `FW-CLAUSEHOOK` family
 * (docs/design/resolution-clause-hook.md): one yes/no, then either a draw or nothing, then the resolving
 * object leaves the stack through the shared [completeClauseResolution].
 *
 * It carries no cost, so unlike the optional discard-then-draw there is no second selection pause and no
 * "can the cost even be paid" pre-check — the "may" is always takeable.
 *
 * **Why the yes/no exists at all**, given that a free draw is nearly always taken: because "nearly always"
 * is not "always". Drawing from an empty library does not lose the game on the spot; it sets the CR 104.3c
 * flag that loses it at the next draw, so declining is a real and occasionally correct play. Making the
 * draw mandatory would delete that decision from the action space an agent trains against (ADR-005), which
 * is the one thing this engine exists not to do.
 */

/**
 * Runs the optional-draw clause of the resolving [entry] (CR 601.3b): pauses for its controller's yes/no.
 * Called by the clause hook after the object's ordinary effect, while it is still on top of the stack.
 */
internal fun orchestrateOptionalDraw(
    state: GameState,
    entry: StackEntry,
    clause: OptionalDraw,
): AdvanceResult {
    val paused =
        state.copy(
            pendingOptionalDraw =
                PendingOptionalDraw(
                    decider = entry.resolutionController,
                    drawCount = clause.drawCount,
                    // CR 113.7c: the source as last known — an ability's source may already be gone.
                    sourceId = entry.resolutionSourceId,
                    sourceCard = entry.resolutionSourceCard,
                ),
        )
    return AdvanceResult.NeedsDecision(paused, pendingOptionalDrawRequest(paused))
}

/** The yes/no the open optional-draw clause is waiting on (CR 601.3b). A pure function of the state. */
internal fun pendingOptionalDrawRequest(state: GameState): DecisionRequest.ChooseYesNo {
    val pending = state.pendingOptionalDraw ?: error("no optional draw is pending")
    return DecisionRequest.ChooseYesNo(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        prompt = "draw ${pending.drawCount}",
        cardObjectId = pending.sourceId,
        card = pending.sourceCard,
    )
}

/**
 * Applies the optional-draw yes/no (CR 601.3b): [accept] `true` draws the clause's cards, `false` draws
 * nothing. Either way the clause closes and the resolving object then leaves the stack through
 * [completeClauseResolution] — the shared completion that knows a spell goes to a graveyard (CR 608.2m)
 * and an ability merely ceases to exist (CR 113.7a).
 */
internal fun applyOptionalDrawYesNo(
    state: GameState,
    accept: Boolean,
): AdvanceResult {
    val pending = state.pendingOptionalDraw ?: error("no optional draw is pending")
    val entry = resolvingClauseEntry(state)
    val cleared = state.copy(pendingOptionalDraw = null)
    val drawn = if (accept) drawCards(cleared, pending.decider, pending.drawCount) else cleared
    return completeClauseResolution(drawn, entry)
}
