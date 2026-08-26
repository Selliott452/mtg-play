package dev.mtgplay.core.state

import dev.mtgplay.core.identity.PlayerId

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

    /**
     * **"Until your next turn"** (CR 611.2, CR 701.38a): the effect ends as [player]'s next turn
     * *begins* — it survives the rest of the turn it was created in, survives every intervening turn,
     * and is gone before anything in [player]'s next turn happens. Additive, flagged core (`W11`) —
     * the Undercity's Arena (goad) and Throne of the Dead Three ("It gains hexproof until your next
     * turn") are the pool's two printings, and they need the same duration.
     *
     * **It is neither of the other two, and the CR 514.2 cleanup could not have ended it anyway.**
     * The effect outlives the turn it began in, so [UntilEndOfTurn] is wrong by a whole turn in the
     * player's favour or against them depending on who is active; and something does end it, so
     * [Indefinite] is wrong forever. The engine ends it with its own turn-based action at the start of
     * [player]'s turn (`endUntilYourNextTurnEffects`), which is where the duration's own words put it.
     *
     * **It records *whose* turn ends it, not *which* turn number does, and that is the whole design.**
     * "Your next turn" cannot be written down as a number when the effect is created without
     * predicting the turn order two turns out — a prediction the engine has no business making, and
     * one a turn-skipping or extra-turn effect would falsify. So this member carries the player, the
     * effect's own [TimedContinuousEffect.createdOnTurn] carries the moment, and [hasEnded] below
     * combines them into a rule that needs no foresight. That is exactly the shape
     * [GameObject.playGrantedTurn] chose for CR 118.5's "until the end of your next turn", one step
     * later in the turn; recording the beginning and deriving the end is the discipline, not a
     * coincidence of two cards.
     *
     * @property player the player whose next turn ends the effect — the controller of the spell or
     *   ability that created it, for both pool printings ("**your** next turn").
     */
    data class UntilYourNextTurn(
        val player: PlayerId,
    ) : EffectDuration {
        /**
         * Whether this duration has run out at a state whose turn is [turnNumber] and whose active
         * player is [activePlayer], for an effect created on turn [createdOnTurn] (CR 611.2).
         *
         * True exactly when the current turn is [player]'s **and** is strictly later than the turn the
         * effect began on. Created on [player]'s own turn N the effect survives N (same number),
         * survives the opponent's N+1 (not [player]'s), and is ended as N+2 begins; created on the
         * opponent's turn N it is ended as N+1 begins. Both are "until your next turn".
         *
         * **The one derivation, shared by the wear-off and by the acceptance invariant that checks the
         * wear-off fired.** Two spellings of the same rule is how an effect comes to be swept a turn
         * early by one and reported healthy by the other. Arithmetic over three recorded facts, so it
         * decides no game rule and stays on this side of ADR-009.
         *
         * **Sharing is right here and was wrong for [GameObject.playGrantedTurn], and the difference is
         * worth stating** because that marker's KDoc once cited this same argument. Both callers here
         * ask the identical question — *"is this effect gone?"* — at moments where the answer is the
         * same. The play grant's two callers did not: its cleanup asks *"does this end now?"* while its
         * enumeration asks *"is this live?"*, and because that permission runs until the **end** of the
         * owner's next turn rather than its beginning, the two answers differ for a whole turn. Sharing
         * a derivation is sound only when the callers share a question.
         *
         * One consequence to know when reading this: like any predicate of this shape it is `false`
         * again on every later turn that is not [player]'s. That is harmless for the wear-off, which
         * fires once at the boundary and removes the effect, but it does mean the invariant cannot
         * catch an effect that leaked past its boundary and is observed on an opponent's turn.
         */
        fun hasEnded(
            activePlayer: PlayerId,
            turnNumber: Int,
            createdOnTurn: Int,
        ): Boolean = activePlayer == player && turnNumber > createdOnTurn
    }
}
