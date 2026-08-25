package dev.mtgplay.core.definition

/**
 * A condition that must hold for a [CastingPermission] to be usable at all (CR 118.9, CR 601.2f) —
 * Land Grant's "**If you have no land cards in hand,** you may reveal your hand rather than pay this
 * spell's mana cost". Additive, flagged core (`FW-ALTCOST`).
 *
 * **This is the first state-conditional thing on a casting permission**, and the distinction it draws
 * is worth stating because the four permissions that predate it hid it. Madness, flashback, escape,
 * plot and rebound are each gated on *where the card is and how it got there* — a marker on the object
 * the engine already had to look at — so their legality is a property of the card's own residence.
 * This one is gated on the **rest of the game state**, read afresh at every priority window, and it can
 * flip between one window and the next without the card moving at all: draw a land and Land Grant's
 * free cast disappears.
 *
 * **The ADR-007 consequence, stated rather than assumed.** The condition reads the caster's own
 * **hidden** hand (CR 400.2). That discloses nothing: cast options are enumerated only for the seat
 * holding priority, over their own cards, so the option's presence or absence is information that seat
 * already has. What the *opponent* may learn is a separate question with a separate answer — they learn
 * it when the cost is actually paid, because paying it reveals the hand (CR 701.16a) and that reveal is
 * public by the same rule that makes every other reveal public. No seat learns anything from the
 * condition being evaluated.
 *
 * **Core/rules split (ADR-009).** This declares *what* the card requires; `mtg-rules` owns evaluating
 * it against the state and excluding the permission from enumeration when it fails (ADR-005) — so a
 * permission whose condition is false is not an option a seat can choose and then dead-end on.
 *
 * Sealed with exactly one member, for the reason [dev.mtgplay.core.state.EffectDuration] is: a card
 * printing a condition this engine does not implement must break the rules-side `when` at compile time
 * rather than quietly evaluating to `true` and handing an agent a free cast it has not earned. The
 * failure mode of a silently-true condition is an enumerated action the rules forbid, which is the
 * ADR-005 defect in its most expensive direction.
 */
sealed interface CastCondition {
    /**
     * "If you have no land cards in hand" (CR 305, CR 400.2) — Land Grant's gate, and the whole of it.
     * The condition is on the **caster's** hand and counts every card whose types include Land
     * (CR 205.2), basic and nonbasic alike; a hand with one Forest fails it.
     *
     * The card being cast is itself in that hand and is deliberately **not** excluded — Land Grant is
     * a sorcery, so it never counts towards its own condition, and excluding it would be an
     * unobservable special case that a future land with the same clause would silently need reversed.
     */
    data object NoLandCardsInHand : CastCondition
}
