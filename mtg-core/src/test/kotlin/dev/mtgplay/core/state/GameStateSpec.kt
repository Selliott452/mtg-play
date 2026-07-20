package dev.mtgplay.core.state

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.Rng
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList

private val alice = PlayerId(0)
private val bob = PlayerId(1)

private fun mountain(
    id: Long,
    owner: PlayerId,
): GameObject = GameObject(ObjectId(id), CardRef("Mountain"), owner)

private fun playerState(vararg library: GameObject): PlayerState =
    PlayerState(
        life = 20,
        library = library.toList().toPersistentList(),
        hand = persistentListOf(),
        graveyard = persistentListOf(),
    )

private fun emptySharedZones(): SharedZones =
    SharedZones(
        battlefield = persistentListOf(),
        stack = persistentListOf(),
        exile = persistentListOf(),
    )

private fun baseState(
    players: PersistentMap<PlayerId, PlayerState> = persistentMapOf(alice to playerState(), bob to playerState()),
    sharedZones: SharedZones = emptySharedZones(),
    nextObjectId: Long = 0,
): GameState =
    GameState(
        players = players,
        turn = Turn(alice, 1, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = sharedZones,
        nextObjectId = nextObjectId,
        rng = Rng(0),
        events = persistentListOf(),
    )

/**
 * Construction invariants of the immutable game state, and the CR 400.7 object-id allocator —
 * the one basic operation P1.1 provides.
 */
class GameStateSpec :
    StringSpec({
        "a two-player state constructs with per-player and shared zones" {
            baseState().players.size shouldBe 2
        }

        "players must be non-empty" {
            shouldThrow<IllegalArgumentException> { baseState(players = persistentMapOf()) }
        }

        "the active player must be seated" {
            shouldThrow<IllegalArgumentException> {
                baseState(players = persistentMapOf(bob to playerState()))
            }
        }

        "player seats are distinct by construction: the players map is keyed by seat" {
            val state = baseState(players = persistentMapOf(alice to playerState(), alice to playerState()))
            state.players.size shouldBe 1
        }

        "CR 704.5a: life may go negative in play, so PlayerState does not constrain it" {
            PlayerState(
                life = -3,
                library = persistentListOf(),
                hand = persistentListOf(),
                graveyard = persistentListOf(),
            ).life shouldBe -3
        }

        "CR 400.7: allocateObjectId returns a fresh id and advances only the counter (ADR-002)" {
            val start = baseState(nextObjectId = 7)
            val (id, next) = start.allocateObjectId()
            id shouldBe ObjectId(7)
            next.nextObjectId shouldBe 8
            start.nextObjectId shouldBe 7
            next.copy(nextObjectId = start.nextObjectId) shouldBe start
        }

        "CR 400.7: successive allocations never repeat ids" {
            var state = baseState()
            val ids =
                buildList {
                    repeat(5) {
                        val (id, next) = state.allocateObjectId()
                        add(id)
                        state = next
                    }
                }
            ids shouldBe (0L..4L).map(::ObjectId)
        }

        "CR 400.7: an object id at or above the allocation counter is rejected" {
            shouldThrow<IllegalArgumentException> {
                baseState(
                    players = persistentMapOf(alice to playerState(mountain(5, alice)), bob to playerState()),
                    nextObjectId = 3,
                )
            }
        }

        "object ids must be unique across all zones" {
            shouldThrow<IllegalArgumentException> {
                baseState(
                    players = persistentMapOf(alice to playerState(mountain(0, alice)), bob to playerState()),
                    sharedZones = emptySharedZones().copy(battlefield = persistentListOf(mountain(0, alice))),
                    nextObjectId = 1,
                )
            }
        }

        "distinct ids across zones construct fine" {
            val state =
                baseState(
                    players = persistentMapOf(alice to playerState(mountain(0, alice)), bob to playerState()),
                    sharedZones = emptySharedZones().copy(battlefield = persistentListOf(mountain(1, alice))),
                    nextObjectId = 2,
                )
            state.nextObjectId shouldBe 2
        }
    })
