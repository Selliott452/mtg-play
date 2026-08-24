package dev.mtgplay.rules

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.effect.mill
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * The mill effect primitive (CR 701.13): the top cards of a library become graveyard objects, in order,
 * narrated by [GameEvent.CardMilled]. Distinct from drawing (CR 121.1 — a short library is not a loss)
 * and from discarding (CR 701.8a — the cards never pass through the hand). The
 * `mtg-rules`-names-no-card rule holds: these are library objects, not a named card.
 */
class MillSpec :
    StringSpec({
        fun startState(
            aliceLibrary: LongRange = 0L..4L,
            bobLibrary: LongRange = 10L..14L,
        ) = twoPlayerState(
            turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
            aliceState = playerWithZones(library = mountains(aliceLibrary, alice)),
            bobState = playerWithZones(library = mountains(bobLibrary, bob)),
            nextObjectId = 100,
        )

        "CR 701.13a: milling two puts the top two library cards into that player's graveyard, top first" {
            val milled = mill(startState(), bob, 2)
            // The two milled cards are new objects (CR 400.7) in the graveyard, in the order milled.
            milled.players
                .getValue(bob)
                .graveyard
                .map { it.card } shouldContainExactly listOf(CardRef("Mountain"), CardRef("Mountain"))
            milled.players
                .getValue(bob)
                .library
                .map { it.id.value } shouldContainExactly listOf(12L, 13L, 14L)
            milled.events
                .filterIsInstance<GameEvent.CardMilled>()
                .map { it.player } shouldContainExactly listOf(bob, bob)
        }

        "CR 701.13a: a mill affects only the named player — the other library and graveyard are untouched" {
            val milled = mill(startState(), bob, 2)
            milled.players
                .getValue(alice)
                .library
                .size shouldBe 5
            milled.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
        }

        "CR 701.13b: milling more cards than the library holds mills as many as possible, and is not a loss" {
            val milled = mill(startState(bobLibrary = 10L..11L), bob, 5)
            milled.players
                .getValue(bob)
                .library
                .shouldBeEmpty()
            milled.players
                .getValue(bob)
                .graveyard
                .size shouldBe 2
            // CR 121.1 vs CR 701.13: milling is not drawing, so no draw-from-empty-library attempt is
            // recorded and the CR 704.5c state-based action has nothing to act on.
            milled.players
                .getValue(bob)
                .attemptedDrawFromEmptyLibrary shouldBe false
            milled.events.filterIsInstance<GameEvent.CardMilled>() shouldHaveSize 2
        }

        "CR 701.13b: milling from an empty library changes nothing and emits nothing" {
            val empty =
                twoPlayerState(
                    turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
                    aliceState = playerWithZones(),
                    bobState = playerWithZones(),
                    nextObjectId = 100,
                )
            val milled = mill(empty, bob, 3)
            milled shouldBeSameInstanceAs empty
            milled.events.shouldBeEmpty()
        }

        "CR 701.13a: milling zero cards is not a mill at all — no state change, no event" {
            val start = startState()
            val untouched = mill(start, bob, 0)
            untouched shouldBeSameInstanceAs start
            untouched.events.shouldBeEmpty()
        }

        "CR 701.13a: a negative mill count is an error, never silently clamped" {
            shouldThrow<IllegalArgumentException> { mill(startState(), bob, -1) }
        }

        "CR 701.13a vs CR 701.8a: a mill is not a discard — it emits CardMilled, never CardDiscarded" {
            val milled = mill(startState(), bob, 2)
            // The discard framework (and with it the CR 702.35a madness replacement) watches discards
            // only; a milled card must therefore never be narrated as one.
            milled.events.filterIsInstance<GameEvent.CardDiscarded>().shouldBeEmpty()
        }
    })
