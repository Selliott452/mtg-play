package dev.mtgplay.rules

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Determinism and purity (ADR-006): equal inputs, equal outputs; replay reproduces the game. */
class ReplaySpec :
    StringSpec({
        "ADR-006: the same config driven the same way twice produces identical states, events, and decisions" {
            val first = playToCompletion(DefaultGameEngine(), mountainConfig())
            val second = playToCompletion(DefaultGameEngine(), mountainConfig())
            second.finalState shouldBe first.finalState
            second.finalState.events shouldBe first.finalState.events
            second.decisions shouldBe first.decisions
            second.result shouldBe first.result
        }

        "ADR-006: replaying the recorded decision log against a fresh engine reproduces the final state" {
            val recorded = playToCompletion(DefaultGameEngine(), mountainConfig())
            val replayed =
                replay(DefaultGameEngine(), mountainConfig(), recorded.decisions)
                    .shouldBeInstanceOf<AdvanceResult.GameOver>()
            replayed.state shouldBe recorded.finalState
            replayed.result shouldBe recorded.result
        }

        "advance is pure: equal inputs produce equal results, and advancing never mutates its input state" {
            val engine = DefaultGameEngine()
            val paused = engine.start(mountainConfig()).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val once = engine.advance(paused.state, respondTo(paused.request))
            val twice = engine.advance(paused.state, respondTo(paused.request))
            twice shouldBe once
            // A fresh start of the same config is bit-identical to the state we already advanced
            // from — proof the advance did not mutate its input.
            val fresh = engine.start(mountainConfig()).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            paused.state shouldBe fresh.state
            paused.request shouldBe fresh.request
        }
    })
