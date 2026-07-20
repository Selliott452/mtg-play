package dev.mtgplay.rules

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** The cleanup step (CR 514): the discard turn-based action and the repeat-cleanup structure. */
class CleanupSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 402.2 and CR 514.1: a hand two over the maximum surfaces a discard of exactly two" {
            val state =
                twoPlayerState(
                    turn = Turn(alice, 3, TurnPhase.ENDING, TurnStep.CLEANUP),
                    aliceState =
                        playerWithZones(
                            hand = mountains(0L..8L, alice),
                            library = mountains(10L..15L, alice),
                        ),
                    bobState = playerWithZones(library = mountains(20L..25L, bob)),
                    nextObjectId = 100,
                )
            val decision = Decision.MultiSelect(DecisionRequestId(alice, 0), listOf(0, 1))
            val result = engine.advance(state, decision).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()

            val aliceAfter = result.state.players.getValue(alice)
            aliceAfter.hand.size shouldBe 7
            aliceAfter.graveyard.map { it.card } shouldBe listOf(CardRef("Mountain"), CardRef("Mountain"))
            // CR 400.7: each discarded card became a new object with a freshly allocated id.
            aliceAfter.graveyard.forEach { it.id.value shouldBeGreaterThanOrEqual 100L }
            result.state.events
                .filterIsInstance<GameEvent.CardDiscarded>()
                .size shouldBe 2

            // The cleanup finished and the next turn began: bob's upkeep window (CR 117.3b).
            result.state.turn shouldBe Turn(bob, 4, TurnPhase.BEGINNING, TurnStep.UPKEEP)
            result.request.shouldBeInstanceOf<DecisionRequest.ChooseAction>().seat shouldBe bob
        }

        "CR 514.3a: a priority round during cleanup is followed by another cleanup step, then the turn ends" {
            val state =
                twoPlayerState(
                    turn = Turn(alice, 9, TurnPhase.ENDING, TurnStep.CLEANUP),
                    aliceState =
                        playerWithZones(
                            hand = mountains(0L..3L, alice),
                            library = mountains(10L..12L, alice),
                        ).copy(priorityStatus = PriorityStatus.HOLDS_PRIORITY),
                    bobState = playerWithZones(library = mountains(20L..22L, bob)),
                    nextObjectId = 50,
                )
            val afterAlicePass =
                engine
                    .advance(state, passDecisionFor(state, alice))
                    .shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            // CR 117.3d: bob receives priority next, still inside the cleanup step.
            afterAlicePass.request.shouldBeInstanceOf<DecisionRequest.ChooseAction>().seat shouldBe bob
            afterAlicePass.state.turn.step shouldBe TurnStep.CLEANUP

            val afterBobPass =
                engine
                    .advance(afterAlicePass.state, passDecisionFor(afterAlicePass.state, bob))
                    .shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            // The completed round during cleanup began another cleanup step (CR 514.3a), which
            // performed no work, so the turn ended and bob's turn began.
            afterBobPass.state.events
                .filterIsInstance<GameEvent.StepBegan>()
                .map(GameEvent.StepBegan::step) shouldBe
                listOf(TurnStep.CLEANUP, TurnStep.UNTAP, TurnStep.UPKEEP)
            afterBobPass.state.turn shouldBe Turn(bob, 10, TurnPhase.BEGINNING, TurnStep.UPKEEP)
        }
    })
