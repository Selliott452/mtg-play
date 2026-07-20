package dev.mtgplay.rules

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.event.LossReason
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.decision.DecisionRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

// Both libraries hold 60 cards, hands take 7, and only the non-starting player draws every
// turn (CR 103.8a skips turn 1's draw): the non-starting seat exhausts 53 draws by turn 106
// and attempts the 54th on turn 108 — the game ends there, at the draw step.
private const val EXPECTED_TURNS: Int = 108
private const val MAXIMUM_HAND_SIZE: Int = 7

// Hand-written expectation, independent of the engine's own position table on purpose.
private fun expectedTurnStructure(
    turnNumber: Int,
    active: PlayerId,
): List<GameEvent> =
    buildList {
        add(GameEvent.TurnBegan(active, turnNumber))
        add(GameEvent.PhaseBegan(TurnPhase.BEGINNING))
        add(GameEvent.StepBegan(TurnStep.UNTAP))
        add(GameEvent.StepBegan(TurnStep.UPKEEP))
        // CR 103.8a: the starting player's first turn has no draw step at all.
        if (turnNumber != 1) add(GameEvent.StepBegan(TurnStep.DRAW))
        // The final turn ends at the draw step: the failed draw loses the game at the
        // state-based-action check before anyone would receive priority (CR 704.5c).
        if (turnNumber != EXPECTED_TURNS) {
            add(GameEvent.PhaseBegan(TurnPhase.PRECOMBAT_MAIN))
            add(GameEvent.PhaseBegan(TurnPhase.COMBAT))
            add(GameEvent.StepBegan(TurnStep.BEGINNING_OF_COMBAT))
            add(GameEvent.StepBegan(TurnStep.DECLARE_ATTACKERS))
            add(GameEvent.StepBegan(TurnStep.DECLARE_BLOCKERS))
            add(GameEvent.StepBegan(TurnStep.COMBAT_DAMAGE))
            add(GameEvent.StepBegan(TurnStep.END_OF_COMBAT))
            add(GameEvent.PhaseBegan(TurnPhase.POSTCOMBAT_MAIN))
            add(GameEvent.PhaseBegan(TurnPhase.ENDING))
            add(GameEvent.StepBegan(TurnStep.END))
            add(GameEvent.StepBegan(TurnStep.CLEANUP))
        }
    }

/**
 * The packet's acceptance game: two players on 60 Mountains, driven to deck-out by the
 * pass-everything, discard-lowest-index test driver.
 */
class FullGameSpec :
    StringSpec({
        val game = playToCompletion()

        "CR 104.3c and CR 704.5c: drawing from an empty library loses and the result names deck-out" {
            game.result shouldBe
                MatchResult(winner = alice, loser = bob, reason = LossReason.ATTEMPTED_DRAW_FROM_EMPTY_LIBRARY)
            game.finalState.events.takeLast(2) shouldBe
                listOf(
                    GameEvent.PlayerLost(bob, LossReason.ATTEMPTED_DRAW_FROM_EMPTY_LIBRARY),
                    GameEvent.GameEnded(winner = alice, loser = bob),
                )
        }

        "CR 500.1: turns alternate between the players with consecutive numbers" {
            val turns = game.finalState.events.filterIsInstance<GameEvent.TurnBegan>()
            turns.map(GameEvent.TurnBegan::turnNumber) shouldBe (1..EXPECTED_TURNS).toList()
            turns.map(GameEvent.TurnBegan::activePlayer) shouldBe
                List(EXPECTED_TURNS) { index -> if (index % 2 == 0) alice else bob }
        }

        "CR 500.1 and CR 103.8a: every phase and step is visited in CR order each turn, turn 1's draw step skipped" {
            val structural =
                game.finalState.events.filter {
                    it is GameEvent.TurnBegan || it is GameEvent.PhaseBegan || it is GameEvent.StepBegan
                }
            val expected =
                (1..EXPECTED_TURNS).flatMap { turnNumber ->
                    expectedTurnStructure(turnNumber, if (turnNumber % 2 == 1) alice else bob)
                }
            structural shouldBe expected
        }

        "CR 502.4: no priority window is surfaced during any untap step" {
            game.pauses.forEach { it.state.turn.step shouldNotBe TurnStep.UNTAP }
        }

        "CR 514.3: cleanup never grants priority in this game — its only pauses are discard requests" {
            game.pauses
                .filter { it.state.turn.step == TurnStep.CLEANUP }
                .forEach { it.request.shouldBeInstanceOf<DecisionRequest.ChooseDiscards>() }
        }

        "CR 402.2 and CR 514.1: cleanup surfaces a one-card discard on every turn after the first" {
            val discardPauses = game.pauses.filter { it.request is DecisionRequest.ChooseDiscards }
            // Turns 2..107 each draw into an eight-card hand; turn 108 never reaches cleanup.
            discardPauses.size shouldBe EXPECTED_TURNS - 2
            discardPauses.forEach { pause ->
                val request = pause.request.shouldBeInstanceOf<DecisionRequest.ChooseDiscards>()
                request.seat shouldBe pause.state.turn.activePlayer
                val handSize =
                    pause.state.players
                        .getValue(request.seat)
                        .hand.size
                request.count shouldBe handSize - MAXIMUM_HAND_SIZE
                request.count shouldBe 1
            }
        }

        "CR 402.2 and CR 514.1: after each cleanup discard the hand is exactly the maximum hand size" {
            game.pauses.forEachIndexed { index, pause ->
                val request = pause.request
                if (request is DecisionRequest.ChooseDiscards) {
                    val next = game.pauses.getOrNull(index + 1).shouldNotBeNull()
                    next.state.players
                        .getValue(request.seat)
                        .hand.size shouldBe MAXIMUM_HAND_SIZE
                }
            }
        }

        "zone conservation: at game end every card sits in library, hand, or graveyard" {
            game.finalState.players.values.forEach { player ->
                player.library.size + player.hand.size + player.graveyard.size shouldBe DECK_SIZE
                player.hand.size shouldBe MAXIMUM_HAND_SIZE
                player.graveyard.size shouldBe DECK_SIZE - OPENING_HAND_SIZE
                player.library.size shouldBe 0
            }
        }

        "CR 117.3b and CR 117.4: in every granting step the active player's window comes first, then the opponent's" {
            val actionPauses = game.pauses.filter { it.request is DecisionRequest.ChooseAction }
            val groups = LinkedHashMap<Triple<Int, TurnPhase, TurnStep?>, MutableList<RecordedPause>>()
            for (pause in actionPauses) {
                val key = Triple(pause.state.turn.number, pause.state.turn.phase, pause.state.turn.step)
                groups.getOrPut(key) { mutableListOf() } += pause
            }
            // Turn 1 grants 9 windows-pairs (no draw step), turns 2..107 grant 10, turn 108
            // only reaches upkeep: 9 + 106 * 10 + 1 positions in all.
            groups.size shouldBe 9 + (EXPECTED_TURNS - 2) * 10 + 1
            groups.values.forEach { pausesInStep ->
                val active =
                    pausesInStep
                        .first()
                        .state.turn.activePlayer
                val nonActive = if (active == alice) bob else alice
                pausesInStep.map { it.request.seat } shouldBe listOf(active, nonActive)
            }
        }
    })
