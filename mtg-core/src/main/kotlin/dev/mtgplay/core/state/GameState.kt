package dev.mtgplay.core.state

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.Rng
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap

/**
 * The complete, immutable state of a game in progress (ADR-002).
 *
 * Every engine transition returns a **new** state; nothing mutates in place, and unchanged
 * substructure is shared between successive states through the persistent collections.
 * Construction validates the basic invariants; game *logic* — advancing turns, moving objects
 * between zones — lives in `mtg-rules`, never here.
 *
 * **Deterministic iteration (architect rule, P1.1).** Every collection reachable from a
 * [GameState] uses the insertion-ordered persistent implementations from
 * `kotlinx.collections.immutable` — `persistentListOf`, `persistentMapOf`, `persistentSetOf` —
 * and never `persistentHashMapOf`/`persistentHashSetOf`, whose iteration order is hash-driven.
 * Enumerated-option indices (ADR-005) and replay (ADR-006) depend on deterministic,
 * insertion-stable iteration order.
 *
 * The [events] log is derived observability (ADR-006): transitions append what happened, for
 * replay display and debugging; rules logic never reads it.
 *
 * @property players each seated player's state, keyed by seat; map insertion order is turn
 *   order, from which APNAP order derives (CR 101.4).
 * @property turn whose turn it is and where within it the game stands (CR 500).
 * @property sharedZones the battlefield, stack, and exile (CR 400.2).
 * @property nextObjectId the [ObjectId] allocation counter; every id in the state is strictly
 *   below it, and ids are never reused (CR 400.7).
 * @property rng the deterministic PRNG state all in-game randomness draws from (ADR-006).
 * @property events the append-only event log; derived, never load-bearing.
 */
data class GameState(
    val players: PersistentMap<PlayerId, PlayerState>,
    val turn: Turn,
    val sharedZones: SharedZones,
    val nextObjectId: Long,
    val rng: Rng,
    val events: PersistentList<GameEvent>,
) {
    init {
        require(players.isNotEmpty()) { "a game has at least one seated player" }
        require(turn.activePlayer in players) { "active player ${turn.activePlayer} is not seated" }
        require(nextObjectId >= 0) { "object-id counter must be non-negative, was $nextObjectId" }
        val ids = allObjects().map(GameObject::id).toList()
        require(ids.size == ids.distinct().size) { "object ids must be unique across all zones" }
        val highest = ids.maxOfOrNull(ObjectId::value)
        require(highest == null || highest < nextObjectId) {
            "CR 400.7: object id $highest is not below the allocation counter $nextObjectId"
        }
    }

    /**
     * Allocates a fresh [ObjectId]: returns the id and the successor state with the counter
     * advanced. Pure — this state is unchanged. Per CR 400.7 an object moving zones becomes a
     * new object, so whatever moves it mints a fresh id here; the zone-move logic itself is
     * rules territory (P1.2+).
     */
    fun allocateObjectId(): Pair<ObjectId, GameState> = ObjectId(nextObjectId) to copy(nextObjectId = nextObjectId + 1)

    private fun allObjects(): Sequence<GameObject> {
        val perPlayer =
            players.values.asSequence().flatMap {
                it.library.asSequence() + it.hand.asSequence() + it.graveyard.asSequence()
            }
        val shared =
            sharedZones.battlefield.asSequence() +
                sharedZones.stack.asSequence() +
                sharedZones.exile.asSequence()
        return perPlayer + shared
    }
}
