package dev.mtgplay.core.definition

/**
 * A "you may draw [drawCount] card(s). **If you do**, discard [discardCount] card(s) [skipDiscardWhen]"
 * clause (CR 601.3b, CR 701.8) — Moon-Circuit Hacker's *"you may draw a card. If you do, discard a card
 * unless this creature entered this turn."* Additive, flagged core (`W9-A`).
 *
 * **It is neither [OptionalDraw] nor [DrawThenDiscard], and it is not the two run one after the other.**
 * [OptionalDraw] is a bare "you may draw" with no tail. [DrawThenDiscard] is Faithless Looting's
 * *mandatory* "draw two, then discard two" — no yes/no at all. This clause chains two pauses where **the
 * second is conditional on the first answer**: decline the draw and there is no discard, because "if you
 * do" is a real conditional and not decoration. Declaring the two clauses side by side would be
 * forbidden anyway ([requireAtMostOneClause]), and would in any case get the conditional wrong — the
 * discard would happen whether or not the draw did.
 *
 * **And the tail is conditional a second time**, on a board fact rather than on a decision:
 * [skipDiscardWhen]. Moon-Circuit Hacker ninjutsu'd in this turn loots for free; the same Hacker
 * connecting on a later turn pays a card for the card. That is the whole tempo argument of the card, and
 * a clause that always discarded, or never did, would delete one half of it.
 *
 * **Core/rules split (ADR-009).** This declares the shape; `mtg-rules` owns the flow — the yes/no
 * (ADR-005 enumerated, ADR-004 no callback), evaluating [skipDiscardWhen] against the state, and the
 * mandatory selection that follows. The discard routes through the CR 614/616 framework, so a discarded
 * madness card is exiled instead.
 *
 * @property drawCount how many cards the accepted "may" draws (Moon-Circuit Hacker's one).
 * @property discardCount how many cards the tail discards when it happens (Moon-Circuit Hacker's one).
 * @property skipDiscardWhen the printed "unless" that can cancel the tail.
 */
data class OptionalDrawThenDiscard(
    val drawCount: Int,
    val discardCount: Int,
    val skipDiscardWhen: DiscardExemption = DiscardExemption.NEVER,
) {
    init {
        require(drawCount >= 1) {
            "CR 601.3b: an optional draw clause draws at least one card, was $drawCount"
        }
        require(discardCount >= 1) {
            "CR 701.8: a 'then discard' clause discards at least one card, was $discardCount"
        }
    }
}

/**
 * The printed "unless" that can cancel an [OptionalDrawThenDiscard]'s discard (CR 701.8) — a *board*
 * condition, checked as the clause runs, as distinct from the yes/no that gates the draw.
 *
 * A closed vocabulary rather than a predicate over [dev.mtgplay.core.state.GameState], because a
 * predicate in core would be a game-rule decision core is not allowed to make (ADR-009) and a lambda
 * would not survive being compared, logged, or replayed. `mtg-rules` decides what satisfies each member,
 * exactly as it decides what satisfies an [EnchantRestriction].
 */
enum class DiscardExemption {
    /** No "unless": the discard always follows an accepted draw. */
    NEVER,

    /**
     * "…unless **this creature entered this turn**" — the discard is skipped when the ability's own
     * source permanent entered the battlefield during the turn now in progress (CR 603.6a,
     * [dev.mtgplay.core.state.GameObject.enteredTurn]).
     *
     * **Not summoning sickness** (CR 302.6), which is a fact about continuous *control* since the start
     * of the controller's most recent turn: the two coincide on many boards and diverge on any creature
     * with haste, and on any creature put onto the battlefield during an opponent's turn. Reading the
     * wrong one would be silently right until the first hasty ninja.
     *
     * The fact is read from the trigger's **last-known information** (CR 603.10) rather than from the
     * battlefield, so a source that dies in response to its own trigger still answers correctly.
     */
    SOURCE_ENTERED_THIS_TURN,
}
