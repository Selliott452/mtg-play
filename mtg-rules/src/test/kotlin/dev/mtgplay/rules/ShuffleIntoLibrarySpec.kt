package dev.mtgplay.rules

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.effect.shuffleIntoOwnersLibrary
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList

/**
 * The shuffle-into-library effect primitive (`FW-SHUFFLEIN`, CR 400.7 + CR 701.20): a graveyard object
 * becomes a **new** library object and the whole library is then randomised. Lembas' leaves-the-
 * battlefield trigger is the first client; the `mtg-rules`-names-no-card rule holds here, so these are
 * plain library and graveyard objects.
 *
 * **ADR-006 is the point of the last two cases.** The randomisation draws from the match-owned [Rng] and
 * returns its successor on the state, so the seed alone decides the order — which is what makes a replay
 * reproduce a library nobody may look at. Both order-dependent assertions pin the seed.
 */
class ShuffleIntoLibrarySpec :
    StringSpec({

        fun stateWith(
            seed: Long = 0L,
            libraryIds: LongRange = 10L..14L,
            graveyardIds: List<Long> = listOf(50L),
        ): GameState =
            GameState(
                players =
                    persistentMapOf(
                        alice to
                            PlayerState(
                                life = STARTING_LIFE,
                                library =
                                    libraryIds
                                        .map { GameObject(ObjectId(it), CardRef("Mountain"), alice) }
                                        .toPersistentList(),
                                hand = persistentListOf(),
                                graveyard =
                                    graveyardIds
                                        .map { GameObject(ObjectId(it), CardRef("Lembas"), alice) }
                                        .toPersistentList(),
                            ),
                        bob to
                            PlayerState(
                                life = STARTING_LIFE,
                                library = persistentListOf(),
                                hand = persistentListOf(),
                                graveyard = persistentListOf(),
                            ),
                    ),
                turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
                sharedZones =
                    SharedZones(
                        battlefield = persistentListOf(),
                        stack = persistentListOf(),
                        exile = persistentListOf(),
                    ),
                nextObjectId = 100,
                rng = Rng(seed),
                events = persistentListOf(),
            )

        "CR 400.7: the card leaves the graveyard for the library as a new object with a fresh id" {
            val shuffled = shuffleIntoOwnersLibrary(stateWith(), ObjectId(50))
            val player = shuffled.players.getValue(alice)

            player.graveyard.shouldBeEmpty()
            player.library.map { it.card } shouldContain CardRef("Lembas")
            // CR 400.7: a new object — the graveyard id is gone and an unissued id took its place.
            player.library.map { it.id.value } shouldNotContain 50L
            player.library
                .single { it.card == CardRef("Lembas") }
                .id shouldBe ObjectId(100)
            shuffled.nextObjectId shouldBe 101L
        }

        "CR 701.20: the whole library is present after the shuffle — nothing is lost or duplicated" {
            val shuffled = shuffleIntoOwnersLibrary(stateWith(), ObjectId(50))
            shuffled.players
                .getValue(alice)
                .library
                .map { it.card } shouldContainExactlyInAnyOrder
                List(5) { CardRef("Mountain") } + CardRef("Lembas")
        }

        "CR 400.7: the move is narrated by CardShuffledIntoLibrary, naming the owner and the new object" {
            val shuffled = shuffleIntoOwnersLibrary(stateWith(), ObjectId(50))
            shuffled.events.filterIsInstance<GameEvent.CardShuffledIntoLibrary>() shouldContainExactly
                listOf(GameEvent.CardShuffledIntoLibrary(alice, ObjectId(100), CardRef("Lembas")))
        }

        "CR 701.20 / ADR-006: the shuffle draws from the match PRNG and advances it" {
            val before = stateWith()
            val shuffled = shuffleIntoOwnersLibrary(before, ObjectId(50))
            // The successor generator rides on the state: nothing may re-use the pre-shuffle one.
            shuffled.rng shouldNotBe before.rng
        }

        "ADR-006: the resulting library order is a pure function of the seed — a known-answer test" {
            // The exact order is the frozen replay contract (ADR-006, Shuffle.kt): if this changes, every
            // recorded match diverges. Pinned for seed 0 over six known ids (the five Mountains 10..14 and
            // the reborn card at 100).
            val shuffled = shuffleIntoOwnersLibrary(stateWith(seed = 0L), ObjectId(50))
            shuffled.players
                .getValue(alice)
                .library
                .map { it.id.value } shouldContainExactly SEED_ZERO_ORDER
        }

        "ADR-006: a different seed gives a different order from the same graveyard and library" {
            val fromZero =
                shuffleIntoOwnersLibrary(stateWith(seed = 0L), ObjectId(50))
                    .players
                    .getValue(alice)
                    .library
                    .map { it.id.value }
            val fromOther =
                shuffleIntoOwnersLibrary(stateWith(seed = 0xC0FFEE), ObjectId(50))
                    .players
                    .getValue(alice)
                    .library
                    .map { it.id.value }
            fromZero shouldNotBe fromOther
        }

        "CR 603.10: an object no longer in any graveyard is not shuffled in — the effect does nothing" {
            val before = stateWith()
            // The id the trigger captured has since moved on and become a different object (CR 400.7).
            val after = shuffleIntoOwnersLibrary(before, ObjectId(999))
            after shouldBe before
        }

        "CR 108.3: the card goes to its **owner's** library — the other seat is untouched" {
            val shuffled = shuffleIntoOwnersLibrary(stateWith(), ObjectId(50))
            shuffled.players
                .getValue(bob)
                .library
                .shouldBeEmpty()
            shuffled.players
                .getValue(bob)
                .graveyard
                .shouldBeEmpty()
        }
    })

/**
 * The library id order [shuffleIntoOwnersLibrary] produces for seed `0` over ids `10..14` plus the
 * reborn `100` — the frozen splitmix64 + Fisher–Yates answer (ADR-006).
 */
private val SEED_ZERO_ORDER: List<Long> = listOf(14L, 12L, 100L, 13L, 10L, 11L)
