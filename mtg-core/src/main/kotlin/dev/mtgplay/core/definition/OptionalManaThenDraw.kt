package dev.mtgplay.core.definition

import dev.mtgplay.core.mana.ManaCost

/**
 * An optional "**you may pay [cost]**. If you do, draw [drawCount] card(s)" clause (CR 601.3b) — Nihil
 * Spellbomb's *"When this artifact is put into a graveyard from the battlefield, you may pay {B}. If you
 * do, draw a card."* Additive, flagged core (`W8-D`).
 *
 * **The fourth "you may … if you do" clause, and the first whose payment is *mana*.** Its three siblings
 * each pay with an object the player already has: [OptionalDiscardDraw] discards a card,
 * [OptionalCostThenDraw] discards a card or sacrifices a land, and [OptionalDraw] pays nothing at all.
 * A mana payment is a different kind of thing — it is not a selection from a list of objects but a
 * *payment plan* over mana sources, the same structure a cast pays with (docs/design/mana-payment.md) —
 * so it could not be spelled as an [OptionalCostMode]. GraveyardHate.kt recorded exactly this as the
 * reason Nihil Spellbomb stayed unencoded when Bojuka Bog landed.
 *
 * **The decider is the resolving object's controller**, unlike [CounterUnlessPaid], the other
 * mid-resolution mana payment. That is not a variation on a theme: CR 118.3a hands the counter's
 * payment to the *targeted spell's* controller, while "you may pay" always means the object's own
 * controller. Sharing one clause type between them would have to carry a decider rule, and the two
 * rules come from different sentences of the CR.
 *
 * **Declining and being unable to pay are the same answer**, as they are for [CounterUnlessPaid]: the
 * draw does not happen. The engine still surfaces the request when nothing can be paid — a
 * decline-only option list — because a uniform decision sequence is what keeps a replay log canonical
 * (ADR-004), and because "can this seat pay {B}?" is not a question the seat should have to answer by
 * seeing its request vanish.
 *
 * **Core/rules split (ADR-009).** This declares *what* may be paid and *what* is drawn; `mtg-rules` owns
 * enumerating the affordable payment plans (ADR-005), executing the chosen one, and drawing.
 *
 * @property cost the mana the clause offers to accept (Nihil Spellbomb's `{B}`); a partial payment is
 *   not a payment (CR 118.4).
 * @property drawCount how many cards a completed payment draws (Nihil Spellbomb's one).
 */
data class OptionalManaThenDraw(
    val cost: ManaCost,
    val drawCount: Int,
) {
    init {
        require(drawCount >= 1) {
            "CR 601.3b: an optional pay-then-draw clause draws at least one card, was $drawCount"
        }
    }
}
