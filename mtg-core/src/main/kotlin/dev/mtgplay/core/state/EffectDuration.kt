package dev.mtgplay.core.state

/**
 * How long a resolution-generated continuous effect lasts (CR 611.2, CR 613.7d) — the duration a
 * [TimedContinuousEffect] carries. Additive, flagged core (`FW-DURATION`,
 * docs/design/duration.md).
 *
 * Sealed so `mtg-rules` ends durations exhaustively: a card printing a duration this engine does not
 * implement breaks the cleanup `when` at compile time rather than quietly expiring at the wrong
 * moment, which is the one failure mode a duration bug takes (docs/design/duration.md §12).
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

    /**
     * **No duration at all** (CR 611.2b): the effect lasts as long as the game does. Additive, flagged
     * core (`FW-TYPECHANGE`) — Kenku Artificer's "that artifact becomes a 0/0 Homunculus artifact
     * creature with flying", which prints no "until" clause of any kind.
     *
     * **CR 611.2b is explicit that this is the default, not the exception**: "if the effect doesn't
     * have a duration, it lasts until the game ends." The engine had only [UntilEndOfTurn] because
     * every resolution-generated effect in the pool until now happened to print one, which made the
     * *absence* of a clause unrepresentable — an omission that would have been paid for by encoding a
     * permanent type change as a turn-long one, i.e. a card that looks right until the cleanup step.
     *
     * Nothing ends it. It is not swept by the CR 514.2 cleanup turn-based action, and there is no
     * other exit: the effect simply keeps naming its
     * [TimedContinuousEffect.affected] object forever. That is not a leak — when the affected object
     * leaves the battlefield the effect applies to nothing for the rest of the game (CR 400.7 makes
     * the returning permanent a different object), exactly as an expired-target
     * [UntilEndOfTurn] effect already does for the remainder of its turn. The store therefore grows
     * monotonically over a game, which is bounded by the number of such effects a deck can generate
     * and is the same growth an equivalent per-object field would have.
     */
    data object Indefinite : EffectDuration
}
