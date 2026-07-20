package dev.mtgplay.rules

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Priority mechanics (CR 117), exercised step by step from the start of a game. */
class PrioritySpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 117.4: after the active player passes, the non-active player receives priority in the same step" {
            val first = engine.start(mountainConfig()).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            first.request.seat shouldBe alice
            val second =
                engine.advance(first.state, respondTo(first.request)).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            second.request.shouldBeInstanceOf<DecisionRequest.ChooseAction>().seat shouldBe bob
            second.state.turn.number shouldBe 1
            second.state.turn.step shouldBe TurnStep.UPKEEP
        }

        "CR 117.4 and CR 500.2: both players passing in succession with an empty stack ends the step" {
            val first = engine.start(mountainConfig()).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val second =
                engine.advance(first.state, respondTo(first.request)).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val third =
                engine
                    .advance(second.state, respondTo(second.request))
                    .shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            // CR 103.8a: turn 1 has no draw step, so upkeep ends straight into the precombat main.
            third.state.turn.phase shouldBe TurnPhase.PRECOMBAT_MAIN
            third.state.turn.step shouldBe null
            // CR 117.3b: the active player receives priority first in the new phase.
            third.request.shouldBeInstanceOf<DecisionRequest.ChooseAction>().seat shouldBe alice
            third.state.events.filterIsInstance<GameEvent.PriorityPassed>() shouldBe
                listOf(GameEvent.PriorityPassed(alice), GameEvent.PriorityPassed(bob))
        }

        "ADR-004: every priority window is surfaced — a pass-only window is still a decision, never auto-passed" {
            val first = engine.start(mountainConfig()).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val request = first.request.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            request.options.size shouldBe 1
            request.options.forEach { it.shouldBeInstanceOf<PriorityOption.Pass>() }
        }
    })
