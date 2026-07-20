package dev.mtgplay.rules

import dev.mtgplay.core.event.LossReason
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf

private fun GameState.withPlayer(
    seat: PlayerId,
    transform: (PlayerState) -> PlayerState,
): GameState = copy(players = players.putting(seat, transform(players.getValue(seat))))

/** The state-based actions of P1.2 (CR 704.5a, CR 704.5c), checked per CR 704.3. */
class StateBasedActionSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        fun pausedAtUpkeep(): AdvanceResult.NeedsDecision =
            engine.start(mountainConfig()).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()

        "CR 704.5a: a player at zero life loses when a player would next receive priority" {
            val paused = pausedAtUpkeep()
            val tampered = paused.state.withPlayer(bob) { it.copy(life = 0) }
            val over =
                engine.advance(tampered, respondTo(paused.request)).shouldBeInstanceOf<AdvanceResult.GameOver>()
            over.result shouldBe MatchResult(winner = alice, loser = bob, reason = LossReason.LIFE_TOTAL_ZERO_OR_LESS)
        }

        "CR 704.5a: negative life loses too — life legally goes below zero before the check" {
            val paused = pausedAtUpkeep()
            val tampered = paused.state.withPlayer(bob) { it.copy(life = -3) }
            val over =
                engine.advance(tampered, respondTo(paused.request)).shouldBeInstanceOf<AdvanceResult.GameOver>()
            over.result.loser shouldBe bob
            over.result.reason shouldBe LossReason.LIFE_TOTAL_ZERO_OR_LESS
        }

        "CR 704.5a: the passing player's own loss is caught before the opponent's window opens" {
            val paused = pausedAtUpkeep()
            val tampered = paused.state.withPlayer(alice) { it.copy(life = 0) }
            val over =
                engine.advance(tampered, respondTo(paused.request)).shouldBeInstanceOf<AdvanceResult.GameOver>()
            over.result shouldBe MatchResult(winner = bob, loser = alice, reason = LossReason.LIFE_TOTAL_ZERO_OR_LESS)
        }

        "CR 704.5c: the state-based action acts on the recorded attempt even with cards in the library" {
            val paused = pausedAtUpkeep()
            val tampered = paused.state.withPlayer(bob) { it.copy(attemptedDrawFromEmptyLibrary = true) }
            val over =
                engine.advance(tampered, respondTo(paused.request)).shouldBeInstanceOf<AdvanceResult.GameOver>()
            over.result shouldBe
                MatchResult(winner = alice, loser = bob, reason = LossReason.ATTEMPTED_DRAW_FROM_EMPTY_LIBRARY)
        }

        "CR 704.5c: an empty library alone does not lose the game — only a recorded attempt does" {
            val paused = pausedAtUpkeep()
            val tampered = paused.state.withPlayer(alice) { it.copy(library = persistentListOf()) }
            // Alice passes; the check before bob's window finds no applicable state-based action.
            engine.advance(tampered, respondTo(paused.request)).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
        }

        "CR 104.4a: all players losing simultaneously is a draw, which fails loudly as unsupported" {
            val paused = pausedAtUpkeep()
            val tampered =
                paused.state
                    .withPlayer(alice) { it.copy(life = 0) }
                    .withPlayer(bob) { it.copy(life = 0) }
            val error =
                shouldThrow<IllegalStateException> { engine.advance(tampered, respondTo(paused.request)) }
            error.message.shouldBeInstanceOf<String>() shouldContain "104.4a"
        }
    })
