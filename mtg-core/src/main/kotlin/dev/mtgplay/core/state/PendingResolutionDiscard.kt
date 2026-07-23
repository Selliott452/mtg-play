package dev.mtgplay.core.state

import dev.mtgplay.core.identity.PlayerId

/**
 * A mandatory "draw N, then discard M" resolution discard the engine is gathering mid-resolution
 * (CR 601.2c) — Faithless Looting. Additive, flagged core (P6.2c). The draw already happened; the resolving
 * spell is still the top object of the stack, and the engine has paused for the [decider]'s selection of
 * exactly [count] hand cards to discard (each through the CR 614/616 framework, so a madness card is exiled
 * instead). On the selection the discards are made and the spell leaves the stack. Non-null only at that
 * mid-resolution pause.
 *
 * @property decider the resolving spell's controller, who drew and must now discard (CR 608.1).
 * @property count how many cards must be discarded (Faithless Looting's two, clamped to the hand size).
 */
data class PendingResolutionDiscard(
    val decider: PlayerId,
    val count: Int,
) {
    init {
        require(count >= 1) { "CR 601.2c: a pending resolution discard removes at least one card, was $count" }
    }
}
