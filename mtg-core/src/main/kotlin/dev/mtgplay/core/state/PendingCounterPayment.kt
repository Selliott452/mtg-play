package dev.mtgplay.core.state

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost

/**
 * A "counter target spell unless its controller pays [cost]" clause paused for that payment (CR 701.5,
 * CR 118.3a) — Force Spike, Spell Pierce. Additive, flagged core (`FW-COUNTER`,
 * docs/design/countering-spells.md §7.1). Non-null only at that mid-resolution pause, where the counter
 * is still the top object of the stack and the spell it targets is still below it.
 *
 * **[decider] is the *targeted* spell's controller** (CR 118.3a), which is what makes this the one
 * pending record whose decider is normally not the resolving object's controller. The pause grants
 * nobody priority: paying is not a cast (CR 605.3b allows the mana abilities, CR 605.3a resolves them
 * without the stack), so the counter's controller gets no window to respond to the answer.
 *
 * The target is held as [counteredObjectId] — the spell's **stack-residence** id — and not as a stack
 * position: a countered spell need not be directly below the counter (two counters can stack above one
 * spell), and the id dies with the residence, so a target that has already left the stack cannot be
 * matched by accident.
 *
 * @property decider the targeted spell's controller, who may pay to save it (CR 118.3a).
 * @property cost the mana [decider] must pay in full (CR 118.3a); a partial payment is not a payment.
 * @property counteredObjectId the targeted spell's stack-residence object id (CR 400.7) — countered if
 *   the payment is declined or impossible.
 */
data class PendingCounterPayment(
    val decider: PlayerId,
    val cost: ManaCost,
    val counteredObjectId: ObjectId,
)
