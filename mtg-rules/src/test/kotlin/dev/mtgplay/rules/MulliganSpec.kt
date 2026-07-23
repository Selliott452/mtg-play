package dev.mtgplay.rules

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.MulliganStage
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The pre-game London mulligan (CR 103.4/103.5): keeping at seven, mulliganing and bottoming, the
 * documented bottom order, both players deciding, and replay determinism through the phase (P6.1).
 */
class MulliganSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        // Mulligans on by default; a fixed starting player keeps the phase order deterministic.
        fun mulliganConfig(
            seed: Long = 0x6113,
            startingPlayer: PlayerId? = alice,
        ): MatchConfig =
            MatchConfig(
                seed = seed,
                libraries = mapOf(alice to mountainDeck(), bob to mountainDeck()),
                startingPlayer = startingPlayer,
            )

        fun stateOf(result: AdvanceResult): GameState =
            when (result) {
                is AdvanceResult.NeedsDecision -> result.state
                is AdvanceResult.GameOver -> result.state
            }

        fun answer(
            result: AdvanceResult,
            select: (DecisionRequest) -> Decision,
        ): AdvanceResult {
            val paused = result.shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            return engine.advance(paused.state, select(paused.request))
        }

        fun keep(result: AdvanceResult): AdvanceResult =
            answer(result) { Decision.SingleSelect(it.id, DecisionRequest.ChooseMulligan.KEEP) }

        fun mulligan(result: AdvanceResult): AdvanceResult =
            answer(result) { Decision.SingleSelect(it.id, DecisionRequest.ChooseMulligan.MULLIGAN) }

        "CR 103.4: the phase opens on the starting player's keep-or-mulligan, then the opponent's" {
            val start = engine.start(mulliganConfig())
            val first = start.shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val mulliganRequest = first.request.shouldBeInstanceOf<DecisionRequest.ChooseMulligan>()
            mulliganRequest.seat shouldBe alice
            mulliganRequest.mulligansTaken shouldBe 0
            first.state.pendingMulligan?.deciding shouldBe alice
            first.state.pendingMulligan?.stage shouldBe MulliganStage.DECLARE

            // Alice keeps at seven; the phase moves to bob without any bottoming.
            val afterAlice = keep(start).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            afterAlice.request.shouldBeInstanceOf<DecisionRequest.ChooseMulligan>().seat shouldBe bob
        }

        "CR 103.5: both players keeping at seven begins turn 1 with full seven-card hands" {
            val afterBothKeep = keep(keep(engine.start(mulliganConfig())))
            val paused = afterBothKeep.shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            paused.request.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            paused.state.pendingMulligan shouldBe null
            paused.state.turn.number shouldBe 1
            paused.state.players.values.forEach { player ->
                player.hand.size shouldBe OPENING_HAND_SIZE
                player.library.size shouldBe DECK_SIZE - OPENING_HAND_SIZE
            }
        }

        "CR 103.5: a single mulligan then keep bottoms exactly one card (hand of six)" {
            val afterMull = mulligan(engine.start(mulliganConfig()))
            val redrawn = afterMull.shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            redrawn.request.shouldBeInstanceOf<DecisionRequest.ChooseMulligan>().mulligansTaken shouldBe 1

            val bottomPrompt = keep(afterMull).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val bottom = bottomPrompt.request.shouldBeInstanceOf<DecisionRequest.ChooseCardsToBottom>()
            bottom.count shouldBe 1
            bottom.options.size shouldBe OPENING_HAND_SIZE

            val afterBottom = engine.advance(bottomPrompt.state, Decision.MultiSelect(bottom.id, listOf(0)))
            keep(afterBottom) // bob keeps; the game then begins
            val alice = stateOf(afterBottom).players.getValue(alice)
            alice.hand.size shouldBe OPENING_HAND_SIZE - 1
            alice.library.size shouldBe DECK_SIZE - (OPENING_HAND_SIZE - 1)
        }

        "CR 103.5: a double mulligan bottoms two cards, in the chosen order, at the library bottom" {
            val afterTwo = mulligan(mulligan(engine.start(mulliganConfig())))
            val bottomPrompt = keep(afterTwo).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val bottom = bottomPrompt.request.shouldBeInstanceOf<DecisionRequest.ChooseCardsToBottom>()
            bottom.count shouldBe 2

            // Select option 1 first, then option 0: option 1 is placed on the bottom, then option 0
            // below it — so the last-selected (option 0) ends up as the very bottom card.
            val firstSelected = bottom.options[1].objectId
            val lastSelected = bottom.options[0].objectId
            val afterBottom = engine.advance(bottomPrompt.state, Decision.MultiSelect(bottom.id, listOf(1, 0)))

            val library = stateOf(afterBottom).players.getValue(alice).library
            library.last().id shouldBe lastSelected
            library[library.size - 2].id shouldBe firstSelected
            stateOf(afterBottom)
                .players
                .getValue(alice)
                .hand.size shouldBe OPENING_HAND_SIZE - 2
        }

        "CR 103.4: both players may mulligan; each bottoms their own mulligan count" {
            var result = engine.start(mulliganConfig())
            result = mulligan(result) // alice mulligans once
            result = keep(result) // alice keeps, now bottoms one
            result = answer(result) { Decision.MultiSelect(it.id, listOf(0)) }
            result = mulligan(result) // bob mulligans once
            result = keep(result) // bob keeps, now bottoms one
            result = answer(result) { Decision.MultiSelect(it.id, listOf(0)) }

            val paused = result.shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            paused.request.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            paused.state.players.values
                .forEach { it.hand.size shouldBe OPENING_HAND_SIZE - 1 }
        }

        "ADR-006: the same seed and mulligan decisions reproduce an identical post-phase state" {
            fun playThroughMulligans(): GameState {
                var result: AdvanceResult = engine.start(mulliganConfig(seed = 99))
                result = mulligan(result)
                result = keep(result)
                result = answer(result) { Decision.MultiSelect(it.id, listOf(2)) }
                result = mulligan(result)
                result = mulligan(result)
                result = keep(result)
                result = answer(result) { Decision.MultiSelect(it.id, listOf(0, 3)) }
                return stateOf(result)
            }
            playThroughMulligans() shouldBe playThroughMulligans()
        }
    })
