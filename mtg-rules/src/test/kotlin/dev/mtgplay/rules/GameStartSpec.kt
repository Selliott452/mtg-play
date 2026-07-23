package dev.mtgplay.rules

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.event.LossReason
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.decision.DecisionRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

private fun distinctNameDeck(): List<CardRef> = List(DECK_SIZE) { CardRef("Card $it") }

private fun distinctNameConfig(seed: Long): MatchConfig =
    MatchConfig(
        seed = seed,
        libraries = mapOf(alice to distinctNameDeck(), bob to distinctNameDeck()),
        startingPlayer = alice,
        mulligansEnabled = false,
    )

private fun libraryNames(result: AdvanceResult): List<List<String>> {
    val state = result.shouldBeInstanceOf<AdvanceResult.NeedsDecision>().state
    return state.players.values.map { player -> player.library.map { it.card.name } }
}

/**
 * Game start (CR 103): seed-determined shuffles and starting player, opening hands, and the
 * first pause of turn 1.
 */
class GameStartSpec :
    StringSpec({
        "CR 103.5: opening hands of seven are drawn and libraries shrink accordingly" {
            val result = DefaultGameEngine().start(mountainConfig())
            val state = result.shouldBeInstanceOf<AdvanceResult.NeedsDecision>().state
            state.players.values.forEach { player ->
                player.hand.size shouldBe OPENING_HAND_SIZE
                player.library.size shouldBe DECK_SIZE - OPENING_HAND_SIZE
                player.life shouldBe STARTING_LIFE
            }
        }

        "CR 502.4 and CR 117.3b: start pauses at the starting player's upkeep priority window" {
            val result = DefaultGameEngine().start(mountainConfig()).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            result.request.shouldBeInstanceOf<DecisionRequest.ChooseAction>().seat shouldBe alice
            result.state.turn.activePlayer shouldBe alice
            result.state.turn.number shouldBe 1
            result.state.turn.phase shouldBe TurnPhase.BEGINNING
            result.state.turn.step shouldBe TurnStep.UPKEEP
            val structural =
                result.state.events.filter {
                    it is GameEvent.GameStarted ||
                        it is GameEvent.TurnBegan ||
                        it is GameEvent.PhaseBegan ||
                        it is GameEvent.StepBegan
                }
            structural shouldBe
                listOf(
                    GameEvent.GameStarted(alice),
                    GameEvent.TurnBegan(alice, 1),
                    GameEvent.PhaseBegan(TurnPhase.BEGINNING),
                    GameEvent.StepBegan(TurnStep.UNTAP),
                    GameEvent.StepBegan(TurnStep.UPKEEP),
                )
        }

        "CR 103.1 and ADR-006: the same seed reproduces the same shuffle; different seeds differ" {
            val first = DefaultGameEngine().start(distinctNameConfig(seed = 1))
            val second = DefaultGameEngine().start(distinctNameConfig(seed = 1))
            val third = DefaultGameEngine().start(distinctNameConfig(seed = 2))
            first shouldBe second
            libraryNames(first) shouldBe libraryNames(second)
            libraryNames(first) shouldNotBe libraryNames(third)
        }

        "CR 103.1: with no configured starting player the engine draws one from the match Rng" {
            val observed =
                (0L..9L).map { seed ->
                    val (expectedIndex, _) = Rng(seed).nextInt(2)
                    val expected = PlayerId(expectedIndex)
                    val config =
                        MatchConfig(
                            seed = seed,
                            libraries = mapOf(alice to mountainDeck(), bob to mountainDeck()),
                            mulligansEnabled = false,
                        )
                    val state =
                        DefaultGameEngine().start(config).shouldBeInstanceOf<AdvanceResult.NeedsDecision>().state
                    state.turn.activePlayer shouldBe expected
                    state.events.first() shouldBe GameEvent.GameStarted(expected)
                    expected
                }
            observed.toSet() shouldBe setOf(alice, bob)
        }

        "CR 704.5c: a player with an empty deck loses at the first state-based-action check" {
            val config =
                MatchConfig(
                    seed = 7,
                    libraries = mapOf(alice to emptyList(), bob to mountainDeck()),
                    startingPlayer = alice,
                    mulligansEnabled = false,
                )
            val over = DefaultGameEngine().start(config).shouldBeInstanceOf<AdvanceResult.GameOver>()
            over.result shouldBe
                MatchResult(winner = bob, loser = alice, reason = LossReason.ATTEMPTED_DRAW_FROM_EMPTY_LIBRARY)
            over.state.events.shouldNotBeEmpty()
            over.state.events.takeLast(2) shouldBe
                listOf(
                    GameEvent.PlayerLost(alice, LossReason.ATTEMPTED_DRAW_FROM_EMPTY_LIBRARY),
                    GameEvent.GameEnded(winner = bob, loser = alice),
                )
        }
    })
