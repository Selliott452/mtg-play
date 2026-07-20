package dev.mtgplay.rules.decision

import dev.mtgplay.core.identity.PlayerId

/**
 * The stable identity of one [DecisionRequest] (ADR-004).
 *
 * A request's identity is `(deciding seat, ordinal)`, where [ordinal] equals the seat's
 * answered-decision count ([dev.mtgplay.core.state.PlayerState.decisionsAnswered]) at the moment
 * the request is surfaced. Each answer increments that count, so every request a seat ever
 * receives has a distinct, strictly increasing ordinal — the identity is collision-free for the
 * whole game and a pure function of the decision history, never of incidental turn structure.
 * That is what makes a recorded `(MatchConfig, List<Decision>)` log unambiguous on replay
 * (ADR-006): a decision applied against the wrong request fails loudly instead of silently
 * answering something else.
 *
 * @property seat the deciding seat this request belongs to.
 * @property ordinal how many decisions [seat] had answered when this request was surfaced.
 */
data class DecisionRequestId(
    val seat: PlayerId,
    val ordinal: Int,
) {
    init {
        require(ordinal >= 0) { "request ordinal must be non-negative, was $ordinal" }
    }
}
