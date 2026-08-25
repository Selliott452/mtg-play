package dev.mtgplay.rules.engine

import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingCast
import dev.mtgplay.rules.AdvanceResult

/*
 * The two CR 601.2b cost announcements a cast may have to make — the optional kicker (CR 702.33a) and
 * the value of a variable cost (CR 107.3b) — split from CastGathering.kt so that file stays within its
 * function budget, the same split PendingCastRequest.kt already is.
 *
 * Both are *announcements*, not payments: answering one only fixes which cost the payment plan
 * enumerated next will be for (CR 601.2f). The mana itself is paid with the rest of the total cost at
 * CR 601.2h, and neither announcement moves anything on its own.
 */

/**
 * Records the CR 601.2b kicker announcement on the open [PendingCast] (CR 702.33a) and suspends for
 * whatever the cast needs next. The cost is not paid here — announcing only fixes *which* cost the
 * payment plan enumerated next will be for (CR 601.2f), and the kicker's mana is paid with the rest of
 * the total cost at CR 601.2h.
 *
 * The announcement is surfaced only when the kicked cost is affordable ([kickerAffordable]), so
 * accepting it always leaves at least one payment plan.
 */
internal fun applyChosenKicker(
    state: GameState,
    kicked: Boolean,
): AdvanceResult {
    val cast = state.pendingCast ?: error("no cast is gathering decisions")
    require(cast.kicked == null) { "CR 601.2b: this cast's kicker announcement is already made" }
    return pauseForNextCastDecision(state.copy(pendingCast = cast.copy(kicked = kicked)))
}

/**
 * Records the CR 601.2b announcement of X on the open [PendingCast] (CR 107.3b) and suspends for the
 * payment choice, which is always what comes next: X is settled last of all the cast's cost decisions.
 *
 * [value] is the announced number itself rather than an option index, translated by the caller, because
 * that is what the cast record and the replay log carry — and because the offered values need not be a
 * contiguous run starting at the index (see [xValueOptions]).
 */
internal fun applyChosenXValue(
    state: GameState,
    value: Int,
): AdvanceResult {
    val cast = state.pendingCast ?: error("no cast is gathering decisions")
    require(cast.chosenX == null) { "CR 601.2b: this cast's value of X is already announced" }
    return pauseForNextCastDecision(state.copy(pendingCast = cast.copy(chosenX = value)))
}
