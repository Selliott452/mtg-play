package dev.mtgplay.rules.decision

/**
 * A player's answer to a [DecisionRequest]: the request it answers plus the selected stable
 * index(es) (ADR-004, ADR-005).
 *
 * The two shapes below cover single- and multi-select requests uniformly, so the recorded
 * decision log stays compact and agent-friendly as request kinds grow. Misuse fails loudly in
 * `GameEngine.advance` (with [IllegalArgumentException]): answering a request other than the
 * pending one, an out-of-range index, a wrong-arity or duplicated multi-select, or the wrong
 * shape for the pending request are all errors, never silently tolerated — replay integrity
 * (ADR-006) depends on it.
 */
sealed interface Decision {
    /** The identity of the [DecisionRequest] this decision answers. */
    val requestId: DecisionRequestId

    /**
     * Answers a single-select request — in P1.2, [DecisionRequest.ChooseAction] — by picking
     * the option at [index].
     *
     * @property index the selected option's stable index within the request's options.
     */
    data class SingleSelect(
        override val requestId: DecisionRequestId,
        val index: Int,
    ) : Decision

    /**
     * Answers a multi-select request — in P1.2, [DecisionRequest.ChooseDiscards] — by picking
     * the options at [indices].
     *
     * @property indices the selected options' stable indices, distinct, in the order the
     *   selections should be applied (for discards: the order the cards are put into the
     *   graveyard, CR 404).
     */
    data class MultiSelect(
        override val requestId: DecisionRequestId,
        val indices: List<Int>,
    ) : Decision
}
