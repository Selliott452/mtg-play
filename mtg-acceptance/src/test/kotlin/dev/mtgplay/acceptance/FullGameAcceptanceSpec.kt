package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.Responders
import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.acceptance.invariant.InvariantChecker
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.event.LossReason
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.MatchResult
import dev.mtgplay.rules.decision.DecisionRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

// A lands-only game between 60-Mountain libraries with alice starting decks the non-starter out
// at the draw step of turn 108 (CR 104.3c) — the P1.2 headline the harness reproduces end-to-end.
private const val EXPECTED_TURNS: Int = 108
private const val EXPECTED_GRAVEYARD: Int = DECK_SIZE - OPENING_HAND_SIZE

/**
 * End-to-end acceptance: a full lands-only game driven through the scripted driver, asserting the
 * P1.2 headline behaviours as one integrated run rather than re-deriving mtg-rules' unit tests.
 * That the game completes at all is itself a claim: the driver invariant-checked every transition.
 */
class FullGameAcceptanceSpec :
    StringSpec({
        val game = ScriptedGame.start(mountainConfig()).playToCompletion(Responders.PASS_AND_DISCARD_LOWEST)
        val events = game.state.events

        "CR 104.3c and CR 704.5c: the game ends in a deck-out and the non-starter loses" {
            game.result shouldBe
                MatchResult(winner = alice, loser = bob, reason = LossReason.ATTEMPTED_DRAW_FROM_EMPTY_LIBRARY)
        }

        "CR 400.2: at game end every card sits accounted for across the three per-seat zones" {
            game.state.players.values.forEach { player ->
                player.library.size shouldBe 0
                player.hand.size shouldBe MAXIMUM_HAND_SIZE
                player.graveyard.size shouldBe EXPECTED_GRAVEYARD
                (player.library.size + player.hand.size + player.graveyard.size) shouldBe DECK_SIZE
            }
        }

        "CR 500.1: exactly the expected number of turns are taken, alternating seats" {
            val turns = events.filterIsInstance<GameEvent.TurnBegan>()
            turns.size shouldBe EXPECTED_TURNS
            turns.map { it.activePlayer } shouldBe
                List(EXPECTED_TURNS) { index -> if (index % 2 == 0) alice else bob }
        }

        "CR 103.8a: the starting player's first turn has no draw step" {
            val turnTwoBegins =
                events.indexOfFirst { it is GameEvent.TurnBegan && it.turnNumber == 2 }
            val turnOneEvents = events.subList(0, turnTwoBegins)
            turnOneEvents
                .filterIsInstance<GameEvent.StepBegan>()
                .map { it.step }
                .contains(TurnStep.DRAW) shouldBe false
        }

        "CR 500.1: a sampled full turn visits its phases and steps in Comprehensive-Rules order" {
            val turnThree = eventsForTurn(events, turnNumber = 3)
            turnThree.filterIsInstance<GameEvent.PhaseBegan>().map { it.phase } shouldBe
                listOf(
                    TurnPhase.BEGINNING,
                    TurnPhase.PRECOMBAT_MAIN,
                    TurnPhase.COMBAT,
                    TurnPhase.POSTCOMBAT_MAIN,
                    TurnPhase.ENDING,
                )
            turnThree.filterIsInstance<GameEvent.StepBegan>().map { it.step } shouldContainInOrder
                listOf(
                    TurnStep.UNTAP,
                    TurnStep.UPKEEP,
                    TurnStep.DRAW,
                    TurnStep.BEGINNING_OF_COMBAT,
                    TurnStep.DECLARE_ATTACKERS,
                    TurnStep.DECLARE_BLOCKERS,
                    TurnStep.COMBAT_DAMAGE,
                    TurnStep.END_OF_COMBAT,
                    TurnStep.END,
                    TurnStep.CLEANUP,
                )
        }

        "CR 514.1: every cleanup discard trims the active player's hand back to the maximum" {
            val pauses = game.pauses
            pauses.forEachIndexed { index, pause ->
                val request = pause.request
                if (request is DecisionRequest.ChooseDiscards) {
                    request.count shouldBe 1
                    pause.state.players
                        .getValue(request.seat)
                        .hand.size shouldBe MAXIMUM_HAND_SIZE + 1
                    // After the discard is answered, the next observed pause shows a legal hand.
                    val next = pauses[index + 1]
                    next.state.players
                        .getValue(request.seat)
                        .hand.size shouldBeLessThanOrEqual MAXIMUM_HAND_SIZE
                }
            }
        }

        "the final state satisfies every invariant" {
            InvariantChecker.check(game.state, game.cardBaseline).shouldBeEmpty()
        }
    })

/** The events emitted during [turnNumber], from its [GameEvent.TurnBegan] up to the next turn's. */
private fun eventsForTurn(
    events: List<GameEvent>,
    turnNumber: Int,
): List<GameEvent> {
    val start = events.indexOfFirst { it is GameEvent.TurnBegan && it.turnNumber == turnNumber }
    val end = events.indexOfFirst { it is GameEvent.TurnBegan && it.turnNumber == turnNumber + 1 }
    return events.subList(start, end)
}
