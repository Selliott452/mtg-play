package dev.mtgplay.core.state

/**
 * How long a resolution-generated continuous effect lasts (CR 611.2, CR 613.7d) — the duration a
 * [TimedContinuousEffect] carries. Additive, flagged core (`FW-DURATION`,
 * docs/design/duration.md).
 *
 * Sealed with exactly one member so `mtg-rules` ends durations exhaustively: a card printing a
 * duration this engine does not implement breaks the cleanup `when` at compile time rather than
 * quietly expiring at the wrong moment, which is the one failure mode a duration bug takes
 * (docs/design/duration.md §12).
 */
sealed interface EffectDuration {
    /**
     * "Until end of turn" (CR 514.2): the effect ends as a turn-based action in the cleanup step of
     * the turn it was created in, simultaneously with the removal of all marked damage.
     *
     * That simultaneity is load-bearing rather than incidental — a creature kept alive only by an
     * until-end-of-turn toughness bonus must not die when the bonus ends, because the damage that
     * would kill it is removed in the same instant (docs/design/duration.md §5.4).
     */
    data object UntilEndOfTurn : EffectDuration
}
