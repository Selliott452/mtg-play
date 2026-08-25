package dev.mtgplay.core.state

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost

/**
 * An optional "you may pay [cost]; if you do, draw [drawCount]" clause paused for that payment
 * (CR 601.3b) — Nihil Spellbomb's dies trigger. Additive, flagged core (`W8-D`). Non-null only at that
 * mid-resolution pause, with the resolving object still on top of the stack.
 *
 * **[decider] is the resolving object's controller**, which is what tells this record apart from
 * [PendingCounterPayment]: that one's decider is the *targeted spell's* controller (CR 118.3a), because
 * a counter's payment is demanded of its victim, while "you may pay" always addresses the object's own
 * controller.
 *
 * The pause grants nobody priority. Paying is not a cast: CR 605.3b permits mana abilities because a
 * resolving object asked for the mana, and CR 605.3a resolves them without the stack.
 *
 * [sourceId] and [sourceCard] are the last-known identity of the object that offered the payment
 * (CR 113.7c), carried for the request's display — and genuinely last-known here rather than
 * incidentally so, because the only card that prints this clause offers it from a trigger that fires
 * *as its source leaves the battlefield*, so the source is already in a graveyard when the request is
 * built.
 *
 * @property decider the resolving object's controller, who may pay (CR 601.3b).
 * @property cost the mana that must be paid in full for the draw to happen (CR 118.4).
 * @property drawCount how many cards a completed payment draws.
 * @property sourceId the offering object's id as last known (CR 113.7c) — the ability's source as of
 *   firing, which for Nihil Spellbomb names a card that has already left the battlefield.
 * @property sourceCard the offering object's printed identity, for display.
 */
data class PendingOptionalManaPayment(
    val decider: PlayerId,
    val cost: ManaCost,
    val drawCount: Int,
    val sourceId: ObjectId,
    val sourceCard: CardRef,
)
