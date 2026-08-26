package dev.mtgplay.rules

import dev.mtgplay.core.definition.LibraryPosition
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.effect.putPermanentIntoOwnersLibrary
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList

/**
 * The put-a-permanent-into-its-owner's-library effect primitive (`W9-F`, CR 400.7 + CR 401.1): a
 * battlefield object becomes a **new** library object at a chosen depth. Deem Inferior's clause is the
 * first client; the `mtg-rules`-names-no-card rule holds here, so these are plain battlefield and
 * library objects.
 *
 * **The depth is the whole reason the primitive exists**, so the two cases that matter most are the two
 * that a `Boolean onTop` could not have told apart: a card seated *under exactly one* other card, and a
 * card seated under all of them. The library is otherwise untouched — no shuffle, no entropy consumed,
 * which is what separates this from `shuffleIntoOwnersLibrary` (ADR-006).
 */
class PutPermanentIntoLibrarySpec :
    StringSpec({

        fun stateWith(
            libraryIds: LongRange = 10L..14L,
            battlefieldOwner: PlayerId = alice,
        ): GameState =
            GameState(
                players =
                    persistentMapOf(
                        alice to
                            PlayerState(
                                life = STARTING_LIFE,
                                library =
                                    libraryIds
                                        .map { GameObject(ObjectId(it), CardRef("Island"), alice) }
                                        .toPersistentList(),
                                hand = persistentListOf(),
                                graveyard = persistentListOf(),
                            ),
                        bob to
                            PlayerState(
                                life = STARTING_LIFE,
                                library =
                                    (20L..24L)
                                        .map { GameObject(ObjectId(it), CardRef("Mountain"), bob) }
                                        .toPersistentList(),
                                hand = persistentListOf(),
                                graveyard = persistentListOf(),
                            ),
                    ),
                turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
                sharedZones =
                    SharedZones(
                        battlefield =
                            persistentListOf(
                                GameObject(ObjectId(50), CardRef("Grizzly Bears"), battlefieldOwner),
                            ),
                        stack = persistentListOf(),
                        exile = persistentListOf(),
                    ),
                nextObjectId = 100,
                rng = Rng(0),
                events = persistentListOf(),
            )

        "CR 401.1: second from the top seats the card under exactly one other card" {
            val moved = putPermanentIntoOwnersLibrary(stateWith(), ObjectId(50), LibraryPosition.SECOND_FROM_TOP)

            moved.players
                .getValue(alice)
                .library
                .map { it.card.name } shouldContainExactly
                listOf("Island", "Grizzly Bears", "Island", "Island", "Island", "Island")
        }

        "CR 401.1: the bottom seats the card under every other card" {
            val moved = putPermanentIntoOwnersLibrary(stateWith(), ObjectId(50), LibraryPosition.BOTTOM)

            moved.players
                .getValue(alice)
                .library
                .map { it.card.name } shouldContainExactly
                listOf("Island", "Island", "Island", "Island", "Island", "Grizzly Bears")
        }

        "CR 400.7: the permanent leaves the battlefield as a new object with a fresh id" {
            val moved = putPermanentIntoOwnersLibrary(stateWith(), ObjectId(50), LibraryPosition.BOTTOM)

            moved.sharedZones.battlefield.shouldBeEmpty()
            // CR 400.7: the battlefield id is gone and an unissued id took its place.
            moved.players
                .getValue(alice)
                .library
                .map { it.id.value } shouldNotContain 50L
            moved.players
                .getValue(alice)
                .library
                .single { it.card == CardRef("Grizzly Bears") }
                .id shouldBe ObjectId(100)
            moved.nextObjectId shouldBe 101L
        }

        "CR 108.3: the card joins its *owner's* library, whoever the battlefield object belonged to" {
            val moved =
                putPermanentIntoOwnersLibrary(
                    stateWith(battlefieldOwner = bob),
                    ObjectId(50),
                    LibraryPosition.SECOND_FROM_TOP,
                )

            moved.players
                .getValue(alice)
                .library
                .map { it.card.name }
                .toSet() shouldBe setOf("Island")
            moved.players
                .getValue(bob)
                .library
                .map { it.card.name } shouldContainExactly
                listOf("Mountain", "Grizzly Bears", "Mountain", "Mountain", "Mountain", "Mountain")
        }

        "CR 401.1: 'second from the top' of a library with no first card seats it on top" {
            val moved =
                putPermanentIntoOwnersLibrary(
                    stateWith(libraryIds = LongRange.EMPTY),
                    ObjectId(50),
                    LibraryPosition.SECOND_FROM_TOP,
                )

            moved.players
                .getValue(alice)
                .library
                .map { it.card.name } shouldContainExactly listOf("Grizzly Bears")
        }

        "ADR-006: the move consumes no seeded entropy — unlike a shuffle-in, it randomises nothing" {
            val before = stateWith()
            val moved = putPermanentIntoOwnersLibrary(before, ObjectId(50), LibraryPosition.BOTTOM)

            moved.rng shouldBe before.rng
        }

        "CR 401.1: the move is narrated as its own event, carrying the depth" {
            val moved = putPermanentIntoOwnersLibrary(stateWith(), ObjectId(50), LibraryPosition.BOTTOM)

            moved.events.filterIsInstance<GameEvent.PermanentPutIntoLibrary>() shouldContainExactly
                listOf(
                    GameEvent.PermanentPutIntoLibrary(
                        alice,
                        ObjectId(100),
                        CardRef("Grizzly Bears"),
                        LibraryPosition.BOTTOM,
                    ),
                )
        }

        "CR 608.2b: a permanent that is not on the battlefield fails loudly rather than moving nothing" {
            shouldThrow<IllegalArgumentException> {
                putPermanentIntoOwnersLibrary(stateWith(), ObjectId(999), LibraryPosition.BOTTOM)
            }
        }
    })
