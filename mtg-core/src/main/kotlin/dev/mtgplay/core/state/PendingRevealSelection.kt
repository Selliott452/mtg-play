package dev.mtgplay.core.state

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/**
 * A "reveal top N, put up to M into hand, rest into graveyard" selection the engine is gathering
 * mid-resolution (CR 701.16) — Malevolent Rumble (M = 1) and Kruphix's Insight (M = 3). Additive,
 * flagged core (P6.2a; [keptIds] added in P6.3). The resolving spell is still the top object of the
 * stack and the [revealedIds] are still the top of the [decider]'s library (revealed but not yet
 * moved); the engine has paused for the choice of which matching card to keep next, and on the last
 * answer moves the kept cards to hand and the rest to the graveyard before the spell leaves the stack.
 * Non-null only at that mid-resolution pause.
 *
 * **Why the keeps accumulate here.** "Put up to three … into your hand" is one choice in the CR, but
 * the engine surfaces choices as enumerated single selections (ADR-005), so it is gathered as up to M
 * rounds of "keep one of the remaining matching cards, or stop". Every subset of the matching cards up
 * to size M is reachable and no information is revealed between rounds, so the reachable outcomes are
 * exactly the CR's legal ones. The partial answer lives in the state, not in a side channel, so the
 * paused game stays a complete record (ADR-004).
 *
 * @property decider the resolving spell's controller, whose library was revealed and who chooses.
 * @property revealedIds the revealed top-of-library object ids, in library (top-first) order.
 * @property keptIds the revealed ids chosen for the hand so far, in the order they were chosen;
 *   always a subset of [revealedIds], never longer than the clause's keep allowance.
 */
data class PendingRevealSelection(
    val decider: PlayerId,
    val revealedIds: PersistentList<ObjectId>,
    val keptIds: PersistentList<ObjectId> = persistentListOf(),
) {
    init {
        require(keptIds.distinct().size == keptIds.size) {
            "CR 701.16: a revealed card is kept at most once, got $keptIds"
        }
        require(revealedIds.containsAll(keptIds)) {
            "CR 701.16: every kept card must be one of the revealed cards, got $keptIds of $revealedIds"
        }
    }
}
