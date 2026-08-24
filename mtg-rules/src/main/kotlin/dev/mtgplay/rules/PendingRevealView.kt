package dev.mtgplay.rules

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject

/**
 * A "reveal top N, keep up to M" selection in progress (CR 701.16) as any seat may see it (ADR-007):
 * the deciding seat, the **revealed** cards themselves, and the cards kept so far.
 *
 * This is the one place library cards cross the view boundary to a non-owning seat, and it is
 * correct: the cards have been *revealed* (CR 701.16 — Malevolent Rumble and Kruphix's Insight reveal
 * the top of the library to all players), so their identities are public to **both** seats even though
 * the rest of the library stays secret. [viewFor] resolves the revealed object ids against the
 * deciding seat's library to expose the actual [GameObject]s here.
 *
 * @property decider the resolving spell's controller, whose library top was revealed (CR 701.16).
 * @property revealed the revealed cards, in reveal (top-first) order; public to both seats.
 * @property kept the revealed cards already chosen for the hand, in the order chosen — non-empty only
 *   part-way through a multi-keep clause (Kruphix's Insight's "up to three"). A sublist of [revealed],
 *   and public for the same reason.
 */
data class PendingRevealView(
    val decider: PlayerId,
    val revealed: List<GameObject>,
    val kept: List<GameObject> = emptyList(),
)
