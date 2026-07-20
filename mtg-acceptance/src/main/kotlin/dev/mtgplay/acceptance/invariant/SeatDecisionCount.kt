package dev.mtgplay.acceptance.invariant

/**
 * One seat's answered-decision count, extracted from a [dev.mtgplay.core.state.GameState] for the
 * [Invariant.ID_SANITY] check.
 *
 * Like [ZoneResidence], this is a plain carrier so the check that a count is non-negative can be
 * tested with a negative value — which a real [dev.mtgplay.core.state.PlayerState] rejects at
 * construction, and so could never otherwise reach the checker.
 *
 * @property seat the zero-based seat index (CR 102).
 * @property count how many decisions the seat has answered
 *   ([dev.mtgplay.core.state.PlayerState.decisionsAnswered]).
 */
data class SeatDecisionCount(
    val seat: Int,
    val count: Int,
)
