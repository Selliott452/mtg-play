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

/**
 * The seed count for a fuzz corpus, honouring the `-PfuzzSeeds` Gradle property (P3.3 scaling
 * knob). The acceptance build surfaces the property as the `fuzzSeeds` system property; when it is
 * absent (the `./gradlew build` case) each corpus keeps its fast [default] so the default runtime
 * stays ~current, and nightly CI passes a large value to scale every corpus at once. A non-positive
 * or unparseable value falls back to [default].
 */
internal fun fuzzSeedCount(default: Int): Int =
    System.getProperty("fuzzSeeds")?.toIntOrNull()?.takeIf { it > 0 } ?: default

/** The seeds `0 until fuzzSeedCount(default)` as Longs — the corpus a suite plays (ADR-006). */
internal fun fuzzSeeds(default: Int): List<Long> = (0L until fuzzSeedCount(default).toLong()).toList()

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
        // P6.1 compatibility path: the existing scripted and corpus suites start games with hands as
        // drawn; the pre-game mulligan phase is exercised by the dedicated mulligan-inclusive specs.
        mulligansEnabled = false,
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
        // P6.1 compatibility path: the existing scripted and corpus suites start games with hands as
        // drawn; the pre-game mulligan phase is exercised by the dedicated mulligan-inclusive specs.
        mulligansEnabled = false,
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
        // P6.1 compatibility path: the existing scripted and corpus suites start games with hands as
        // drawn; the pre-game mulligan phase is exercised by the dedicated mulligan-inclusive specs.
        mulligansEnabled = false,
        startingPlayer = startingPlayer,
    )

/**
 * A sublethal Bolt count: with 2 Bolts per deck even every Bolt in the game aimed at one
 * player totals 12 damage < 20 starting life, so a bolt death is impossible and every game
 * must end as a deck-out (CR 704.5c) — the corpus half that guarantees deck-out endings.
 */
internal const val SUBLETHAL_BOLT_COUNT: Int = 2

/**
 * A creature-aggro deck (CR 100.1) for the asymmetric mixed-matchup corpus (P3.3): a heavy body
 * count — Grizzly Bears on a Forest/Mountain base with a light Bolt package — so it reliably floods
 * the board and can win by combat, while its Bolts let it also close (and interact) via burn. Tuned
 * (with [mixedMatchupConfig]) so that, against the creatureless burn deck across the corpus, both
 * win paths occur: creatures connect for lethal combat damage (CR 704.5g never applies to the
 * blocker-less defender — every point lands) and Bolts deal lethal face damage (CR 704.5a).
 */
internal fun creatureAggroDeck(size: Int = DECK_SIZE): List<CardRef> {
    val bears = List(AGGRO_DECK_BEARS) { CardRef("Grizzly Bears") }
    val bolts = List(AGGRO_DECK_BOLTS) { CardRef("Lightning Bolt") }
    val forests = List(AGGRO_DECK_FORESTS) { CardRef("Forest") }
    val mountainCount = size - AGGRO_DECK_BEARS - AGGRO_DECK_BOLTS - AGGRO_DECK_FORESTS
    return bears + bolts + forests + List(mountainCount) { CardRef("Mountain") }
}

/** Grizzly Bears in the creature-aggro deck (the bodies that carry the combat-kill win path). */
internal const val AGGRO_DECK_BEARS: Int = 12

/** Lightning Bolts in the creature-aggro deck (the burn package and its removal/fizzle interaction). */
internal const val AGGRO_DECK_BOLTS: Int = 10

/** Forests in the creature-aggro deck (the green half of the mana base for {1}{G} bears). */
internal const val AGGRO_DECK_FORESTS: Int = 16

/**
 * The asymmetric mixed-matchup config (P3.3): [alice] on a pure burn deck (no creatures at all,
 * [burnDeck]) versus [bob] on a [creatureAggroDeck], `MvpCards` definitions, seed-determined
 * (ADR-006). The starting player is seed-chosen. This is the corpus that exercises both archetype
 * win paths against each other — burn racing an aggressive creature board.
 */
internal fun mixedMatchupConfig(seed: Long): MatchConfig =
    MatchConfig(
        seed = seed,
        libraries = mapOf(alice to burnDeck(STANDARD_BOLT_COUNT), bob to creatureAggroDeck()),
        definitions = MvpCards.definitions,
        // P6.1 compatibility path: the existing scripted and corpus suites start games with hands as
        // drawn; the pre-game mulligan phase is exercised by the dedicated mulligan-inclusive specs.
        mulligansEnabled = false,
        startingPlayer = null,
    )

/**
 * A GW-Auras-style deck (CR 100.1) for the P4.2 aura corpus: Grizzly Bears bodies to enchant, the
 * full spread of the seven Bogles Auras, a Lightning Bolt removal package (which kills enchanted
 * creatures, exercising the CR 704.5m aura fall-off), and a three-colour Forest/Plains/Mountain mana
 * base. Tuned (with [boglesAuraConfig]) so a random-legal corpus reliably enchants creatures, kills
 * some of them while enchanted (fall-off), and terminates. `MvpCards` definitions only.
 */
internal fun boglesAuraDeck(size: Int = DECK_SIZE): List<CardRef> {
    val bears = List(AURA_DECK_BEARS) { CardRef("Grizzly Bears") }
    val auras =
        listOf(
            "Rancor" to 3,
            "Ethereal Armor" to 3,
            "Armadillo Cloak" to 2,
            "Cartouche of Solidarity" to 2,
            "Sentinel's Eyes" to 2,
            "Ancestral Mask" to 2,
            "Abundant Growth" to 2,
        ).flatMap { (name, count) -> List(count) { CardRef(name) } }
    val bolts = List(AURA_DECK_BOLTS) { CardRef("Lightning Bolt") }
    val forests = List(AURA_DECK_FORESTS) { CardRef("Forest") }
    val plains = List(AURA_DECK_PLAINS) { CardRef("Plains") }
    val fixedCount = bears.size + auras.size + bolts.size + forests.size + plains.size
    val mountains = List(size - fixedCount) { CardRef("Mountain") }
    return bears + auras + bolts + forests + plains + mountains
}

/** Grizzly Bears in the aura deck (the bodies that carry, and lose, the Auras). */
internal const val AURA_DECK_BEARS: Int = 12

/** Lightning Bolts in the aura deck (removal that kills enchanted creatures -> CR 704.5m fall-off). */
internal const val AURA_DECK_BOLTS: Int = 8

/** Forests in the aura deck (the green half of the mana base: Rancor, Ancestral Mask, bears). */
internal const val AURA_DECK_FORESTS: Int = 10

/** Plains in the aura deck (the white half: Cartouche, Sentinel's Eyes, Ethereal Armor). */
internal const val AURA_DECK_PLAINS: Int = 8

/**
 * A symmetric two-player P4.2 aura config: both seats on [boglesAuraDeck]s, `MvpCards` definitions,
 * seed-determined (ADR-006). The starting player is seed-chosen. The corpus this drives is where the
 * real Bogles Auras get cast, attached, and torn off dying creatures across random-legal playouts.
 */
internal fun boglesAuraConfig(seed: Long): MatchConfig =
    MatchConfig(
        seed = seed,
        libraries = mapOf(alice to boglesAuraDeck(), bob to boglesAuraDeck()),
        definitions = MvpCards.definitions,
        // P6.1 compatibility path: the existing scripted and corpus suites start games with hands as
        // drawn; the pre-game mulligan phase is exercised by the dedicated mulligan-inclusive specs.
        mulligansEnabled = false,
        startingPlayer = null,
    )

/**
 * A GW-Bogles keyword deck (CR 100.1) for the P5.3 corpus: the three real hexproof one-drops
 * (Gladecover Scout, Slippery Bogle, Silhana Ledgewalker) plus Grizzly Bears bodies, a heavy Rancor
 * package (grants trample and +2/+0, so a blocked Rancor'd attacker surfaces the trample-assignment
 * decision), and a small Lightning Bolt package on a Forest/Mountain base. Tuned (with
 * [boglesKeywordConfig]) so a random-legal corpus reliably (a) has opponents holding hexproof
 * creatures the enumerator must route targeting around, and (b) produces real trample assignments.
 */
internal fun boglesKeywordDeck(size: Int = DECK_SIZE): List<CardRef> {
    val hexproof =
        listOf("Gladecover Scout", "Slippery Bogle", "Silhana Ledgewalker")
            .flatMap { name -> List(KEYWORD_DECK_HEXPROOF_EACH) { CardRef(name) } }
    val bears = List(KEYWORD_DECK_BEARS) { CardRef("Grizzly Bears") }
    val rancors = List(KEYWORD_DECK_RANCORS) { CardRef("Rancor") }
    val bolts = List(KEYWORD_DECK_BOLTS) { CardRef("Lightning Bolt") }
    val mountains = List(KEYWORD_DECK_MOUNTAINS) { CardRef("Mountain") }
    val fixed = hexproof.size + bears.size + rancors.size + bolts.size + mountains.size
    val forests = List(size - fixed) { CardRef("Forest") }
    return hexproof + bears + rancors + bolts + mountains + forests
}

/** Copies of each hexproof one-drop in the keyword deck. */
internal const val KEYWORD_DECK_HEXPROOF_EACH: Int = 4

/** Grizzly Bears in the keyword deck (bodies to attack, block, and wear Rancor). */
internal const val KEYWORD_DECK_BEARS: Int = 8

/** Rancors in the keyword deck (the trample grant that makes blocked attackers surface the choice). */
internal const val KEYWORD_DECK_RANCORS: Int = 8

/** Lightning Bolts in the keyword deck (opponent targeting the hexproof enumerator must route around). */
internal const val KEYWORD_DECK_BOLTS: Int = 6

/** Mountains in the keyword deck (the red half of the mana base for the Bolts). */
internal const val KEYWORD_DECK_MOUNTAINS: Int = 6

/**
 * A symmetric two-player P5.3 keyword config: both seats on [boglesKeywordDeck]s, `MvpCards`
 * definitions, seed-determined (ADR-006). The corpus this drives exercises hexproof targeting
 * exclusion and real trample-assignment decisions across random-legal playouts.
 */
internal fun boglesKeywordConfig(seed: Long): MatchConfig =
    MatchConfig(
        seed = seed,
        libraries = mapOf(alice to boglesKeywordDeck(), bob to boglesKeywordDeck()),
        definitions = MvpCards.definitions,
        // P6.1 compatibility path: the existing scripted and corpus suites start games with hands as
        // drawn; the pre-game mulligan phase is exercised by the dedicated mulligan-inclusive specs.
        mulligansEnabled = false,
        startingPlayer = null,
    )

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
