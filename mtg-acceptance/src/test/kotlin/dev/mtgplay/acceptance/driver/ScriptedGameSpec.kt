package dev.mtgplay.acceptance.driver

import dev.mtgplay.acceptance.invariant.CardCensus
import dev.mtgplay.acceptance.invariant.Invariant
import dev.mtgplay.acceptance.invariant.InvariantChecker
import dev.mtgplay.acceptance.mountainConfig
import dev.mtgplay.core.event.LossReason
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.DefaultGameEngine
import dev.mtgplay.rules.GameEngine
import dev.mtgplay.rules.MatchConfig
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.toPersistentMap

/**
 * The scripted-game driver: its fluent stepping, its accumulated replay record, and — the load-
 * bearing guarantee — that it invariant-checks every single transition and fails loudly.
 */
class ScriptedGameSpec :
    StringSpec({

        "start advances to the first decision and captures the baseline card census" {
            val game = ScriptedGame.start(mountainConfig())
            game.isOver shouldBe false
            game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            // 60 + 60 Mountains are present from the first state and become the conserved baseline.
            game.cardBaseline shouldBe CardCensus.of(game.state)
        }

        "the driver runs the checker on every transition — a counting spy proves it" {
            var invocations = 0
            val counting =
                StateChecker { state, baseline ->
                    invocations++
                    InvariantChecker.check(state, baseline)
                }
            val game = ScriptedGame.start(mountainConfig(), checker = counting)
            invocations shouldBe 1 // the initial state

            game.pass().pass()
            invocations shouldBe 3 // one further invocation per advance
        }

        "the driver fails loudly when a transition produces an invariant-violating state (CR 117.1a)" {
            // An engine that corrupts every advance into a two-priority-holder state; the real
            // engine's start still seeds a valid first state so the driver can begin.
            val corrupting =
                object : GameEngine {
                    private val real = DefaultGameEngine()

                    override fun start(config: MatchConfig): AdvanceResult = real.start(config)

                    override fun advance(
                        state: GameState,
                        decision: Decision,
                    ): AdvanceResult {
                        val holding = PriorityStatus.HOLDS_PRIORITY
                        val bothHold =
                            state.players
                                .mapValues { (_, player) -> player.copy(priorityStatus = holding) }
                                .toPersistentMap()
                        val anyRequest = (real.start(mountainConfig()) as AdvanceResult.NeedsDecision).request
                        return AdvanceResult.NeedsDecision(state.copy(players = bothHold), anyRequest)
                    }
                }
            val game = ScriptedGame.start(mountainConfig(), engine = corrupting)
            val thrown = shouldThrow<InvariantViolationException> { game.pass() }
            thrown.violations.map { it.invariant } shouldContain Invariant.PRIORITY
            // The exception carries the replay record up to the failure.
            thrown.decisions.size shouldBe 1
        }

        "playToCompletion drives a lands-only game to deck-out and records the decision log" {
            val game = ScriptedGame.start(mountainConfig()).playToCompletion(Responders.PASS_AND_DISCARD_LOWEST)
            game.isOver.shouldBeTrue()
            game.result?.reason shouldBe LossReason.ATTEMPTED_DRAW_FROM_EMPTY_LIBRARY
            game.decisions.size shouldBeGreaterThan 0
            // Every recorded transition passed the checker (else start/play would have thrown).
            InvariantChecker.check(game.state, game.cardBaseline) shouldBe emptyList()
        }

        "playToCompletion fails loudly when a game outruns its turn cap" {
            shouldThrow<IllegalStateException> {
                ScriptedGame
                    .start(mountainConfig())
                    .playToCompletion(Responders.PASS_AND_DISCARD_LOWEST, turnCap = 3)
            }
        }

        "passUntil advances with the default policy until its predicate holds" {
            val game = ScriptedGame.start(mountainConfig()).passUntil { it.turn.number >= 4 }
            game.state.turn.number shouldBeGreaterThan 3
        }

        "pass and discard step through the pending requests fluently" {
            // The default policy stops at the first cleanup pause, which is a forced discard.
            val game = ScriptedGame.start(mountainConfig()).passUntil { it.turn.step == TurnStep.CLEANUP }
            val discard = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseDiscards>()
            discard.count shouldBe 1
            val handBefore =
                game.state.players
                    .getValue(discard.seat)
                    .hand.size
            handBefore shouldBe 8
            game.discard(0)
            // Answering the discard advanced the game onward past that cleanup step.
            game.isOver shouldBe false
        }
    })
