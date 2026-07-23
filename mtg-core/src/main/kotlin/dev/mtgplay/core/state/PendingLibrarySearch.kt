package dev.mtgplay.core.state

import dev.mtgplay.core.identity.PlayerId

/**
 * A "search your library, put one card into your hand, then shuffle" the engine is resolving as part of an
 * activated ability (CR 701.18) — Ash Barrens' basic landcycling. Additive, flagged core (P6.2c). The
 * activated ability is still the top object of the stack (its declaration carries the search filter); the
 * engine has paused for the [decider]'s choice of which matching card (if any) to find. On the choice the
 * found card moves to the hand, the library is shuffled through the match PRNG (ADR-006), and the ability
 * leaves the stack. Non-null only at that mid-resolution pause.
 *
 * @property decider the ability's controller, whose library is searched and who chooses (CR 701.18).
 */
data class PendingLibrarySearch(
    val decider: PlayerId,
)
