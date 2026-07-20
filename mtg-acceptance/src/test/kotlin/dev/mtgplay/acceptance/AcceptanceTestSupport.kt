package dev.mtgplay.acceptance

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.rules.MatchConfig
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList

/*
 * Shared fixtures for the acceptance suites. The two seats, the lands-only decks, and the
 * handcrafted-state builders mirror the P1.2 engine's own test support so the acceptance tests
 * read against the same vocabulary the harness durably replaces.
 */

internal val alice = PlayerId(0)
internal val bob = PlayerId(1)

internal const val DECK_SIZE: Int = 60
internal const val OPENING_HAND_SIZE: Int = 7
internal const val MAXIMUM_HAND_SIZE: Int = 7
internal const val STARTING_LIFE: Int = 20

/** A lands-only deck: [size] copies of Mountain, the packet's acceptance deck. */
internal fun mountainDeck(size: Int = DECK_SIZE): List<CardRef> = List(size) { CardRef("Mountain") }

/** The standard two-player acceptance config: both seats on 60 Mountains, seed-determined. */
internal fun mountainConfig(
    seed: Long = 0x5EED,
    startingPlayer: PlayerId? = alice,
): MatchConfig =
    MatchConfig(
        seed = seed,
        libraries = mapOf(alice to mountainDeck(), bob to mountainDeck()),
        startingPlayer = startingPlayer,
    )

/** [GameObject]s of one printed card with the given ids, for handcrafted states. */
internal fun cards(
    name: String,
    ids: LongRange,
    owner: PlayerId,
): PersistentList<GameObject> = ids.map { GameObject(ObjectId(it), CardRef(name), owner) }.toPersistentList()

/** Mountain [GameObject]s with the given ids, for handcrafted states. */
internal fun mountains(
    ids: LongRange,
    owner: PlayerId,
): PersistentList<GameObject> = cards("Mountain", ids, owner)

/** A [PlayerState] with the given zones and defaults for everything else. */
internal fun playerWithZones(
    life: Int = STARTING_LIFE,
    library: PersistentList<GameObject> = persistentListOf(),
    hand: PersistentList<GameObject> = persistentListOf(),
    graveyard: PersistentList<GameObject> = persistentListOf(),
): PlayerState =
    PlayerState(
        life = life,
        library = library,
        hand = hand,
        graveyard = graveyard,
    )

/** A handcrafted two-player [GameState] — a valid engine input by construction (ADR-004). */
internal fun twoPlayerState(
    turn: Turn,
    aliceState: PlayerState,
    bobState: PlayerState,
    nextObjectId: Long,
    rng: Rng = Rng(0),
): GameState =
    GameState(
        players = persistentMapOf(alice to aliceState, bob to bobState),
        turn = turn,
        sharedZones =
            SharedZones(
                battlefield = persistentListOf(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = nextObjectId,
        rng = rng,
        events = persistentListOf(),
    )
