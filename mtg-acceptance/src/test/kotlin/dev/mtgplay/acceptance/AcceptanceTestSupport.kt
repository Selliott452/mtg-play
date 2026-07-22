package dev.mtgplay.acceptance

import dev.mtgplay.cards.MvpCards
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
 * Shared fixtures for the acceptance suites. The two seats, the real-card decks (P2.2: every
 * acceptance game runs on `MvpCards` definitions through a normal `MatchConfig` — no fixture
 * cards, no doctored states), and the handcrafted-state builders for checker unit tests.
 */

internal val alice = PlayerId(0)
internal val bob = PlayerId(1)

internal const val DECK_SIZE: Int = 60
internal const val OPENING_HAND_SIZE: Int = 7
internal const val MAXIMUM_HAND_SIZE: Int = 7
internal const val STARTING_LIFE: Int = 20

/** A lands-only deck: [size] copies of Mountain, the packet's acceptance deck. */
internal fun mountainDeck(size: Int = DECK_SIZE): List<CardRef> = List(size) { CardRef("Mountain") }

/**
 * The standard two-player acceptance config: both seats on 60 real Mountains ([MvpCards]),
 * seed-determined.
 */
internal fun mountainConfig(
    seed: Long = 0x5EED,
    startingPlayer: PlayerId? = alice,
): MatchConfig =
    MatchConfig(
        seed = seed,
        libraries = mapOf(alice to mountainDeck(), bob to mountainDeck()),
        definitions = MvpCards.definitions,
        startingPlayer = startingPlayer,
    )

/**
 * A burn deck (CR 100.1): [bolts] Lightning Bolts and Mountains up to [size] cards — the
 * P2.2 real-card acceptance deck.
 */
internal fun burnDeck(
    bolts: Int,
    size: Int = DECK_SIZE,
): List<CardRef> = List(bolts) { CardRef("Lightning Bolt") } + List(size - bolts) { CardRef("Mountain") }

/**
 * A two-player real-card config: both seats on [burnDeck]s of [bolts] Bolts, `MvpCards`
 * definitions, seed-determined (ADR-006).
 */
internal fun burnConfig(
    seed: Long,
    bolts: Int = STANDARD_BOLT_COUNT,
    startingPlayer: PlayerId? = null,
): MatchConfig =
    MatchConfig(
        seed = seed,
        libraries = mapOf(alice to burnDeck(bolts), bob to burnDeck(bolts)),
        definitions = MvpCards.definitions,
        startingPlayer = startingPlayer,
    )

/** The standard burn-deck Bolt count: 40 Bolts + 20 Mountains forces constant action. */
internal const val STANDARD_BOLT_COUNT: Int = 40

/**
 * A creature-combat deck (CR 100.1): Grizzly Bears (`{1}{G}` 2/2) and Lightning Bolts (`{R}`) on a
 * Forest/Mountain mana base — the P3.2 corpus deck. The mix is tuned so the random corpus reliably
 * exhibits all three P3.2 death paths: cheap 2/2 bodies come down and trade in combat (each 2/2
 * kills the other, CR 704.5g), Bolts finish 2/2s (3 ≥ 2, CR 704.5g) and go to the face, and a Bolt
 * aimed at a creature that a response Bolt then kills fizzles (CR 608.2b). Both colours are needed
 * every game, but with 24 lands a colour screw is rare.
 */
internal fun creatureDeck(size: Int = DECK_SIZE): List<CardRef> {
    val bolts = List(CREATURE_DECK_BOLTS) { CardRef("Lightning Bolt") }
    val bears = List(CREATURE_DECK_BEARS) { CardRef("Grizzly Bears") }
    val forests = List(CREATURE_DECK_FORESTS) { CardRef("Forest") }
    val mountainCount = size - CREATURE_DECK_BOLTS - CREATURE_DECK_BEARS - CREATURE_DECK_FORESTS
    return bolts + bears + forests + List(mountainCount) { CardRef("Mountain") }
}

/** Lightning Bolts per creature-combat deck (bolt kills, face damage, and the fizzle response). */
internal const val CREATURE_DECK_BOLTS: Int = 30

/** Grizzly Bears per creature-combat deck (the bodies that trade in combat). */
internal const val CREATURE_DECK_BEARS: Int = 8

/** Forests per creature-combat deck (the green half of the mana base). */
internal const val CREATURE_DECK_FORESTS: Int = 10

/**
 * A two-player creature-combat config: both seats on [creatureDeck]s, `MvpCards` definitions,
 * seed-determined (ADR-006). [startingPlayer] `null` lets the seed pick who starts.
 */
internal fun creatureConfig(
    seed: Long,
    startingPlayer: PlayerId? = null,
): MatchConfig =
    MatchConfig(
        seed = seed,
        libraries = mapOf(alice to creatureDeck(), bob to creatureDeck()),
        definitions = MvpCards.definitions,
        startingPlayer = startingPlayer,
    )

/**
 * A sublethal Bolt count: with 2 Bolts per deck even every Bolt in the game aimed at one
 * player totals 12 damage < 20 starting life, so a bolt death is impossible and every game
 * must end as a deck-out (CR 704.5c) — the corpus half that guarantees deck-out endings.
 */
internal const val SUBLETHAL_BOLT_COUNT: Int = 2

/** Generous turn cap for real-card playouts; a no-death game decks out near turn 108. */
internal const val REAL_CARD_TURN_CAP: Int = 130

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
