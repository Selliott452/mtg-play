package dev.mtgplay.rules

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.updatePlayer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The play-land special action (CR 116.2a, CR 305): legality gates on enumeration (ADR-005),
 * execution as a single stack-less transition, and the priority consequences (CR 116.4,
 * CR 117.4). Uses the fixture lands — engine tests never name a real card.
 */
class PlayLandSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        fun windowOf(state: GameState) = pausedRequestOf<DecisionRequest.ChooseAction>(state)

        "CR 115.2a and CR 400.7: playing a land moves it from hand to the battlefield untapped, as a new object" {
            val start =
                fixtureState(
                    aliceSetup = SeatSetup(hand = listOf("Fixture Mountain")),
                    bobSetup = SeatSetup(),
                )
            val after = engine.advance(start, playLandDecision(windowOf(start), "Fixture Mountain"))
            val state = after.pausedState
            val land = state.sharedZones.battlefield.single()
            land.card shouldBe CardRef("Fixture Mountain")
            land.tapped.shouldBeFalse()
            // CR 400.7: the battlefield object is new — its id was allocated by this transition.
            land.id.value shouldBeGreaterThanOrEqual start.nextObjectId
            state.players
                .getValue(alice)
                .hand
                .shouldBeEmpty()
            state.sharedZones.stack.shouldBeEmpty()
            state.events.filterIsInstance<GameEvent.LandPlayed>() shouldBe
                listOf(GameEvent.LandPlayed(alice, land.id, CardRef("Fixture Mountain")))
        }

        "CR 116.4: the player who plays a land retains priority; CR 117.4: pass-flags reset" {
            val start =
                fixtureState(
                    aliceSetup = SeatSetup(hand = listOf("Fixture Mountain")),
                    bobSetup = SeatSetup(),
                ).updatePlayer(bob) { it.copy(priorityStatus = PriorityStatus.HAS_PASSED) }
            val after = engine.advance(start, playLandDecision(windowOf(start), "Fixture Mountain"))
            // The land player receives priority again (CR 116.4) in a fresh round: an action was
            // taken, so bob's earlier pass no longer counts toward CR 117.4's succession.
            after.pending<DecisionRequest.ChooseAction>().seat shouldBe alice
            val state = after.pausedState
            state.players.getValue(alice).priorityStatus shouldBe PriorityStatus.HOLDS_PRIORITY
            state.players.getValue(bob).priorityStatus shouldBe PriorityStatus.NONE
        }

        "CR 305.2: one land per turn — the second play is not enumerated the same turn" {
            val start =
                fixtureState(
                    aliceSetup = SeatSetup(hand = listOf("Fixture Mountain", "Fixture Forest")),
                    bobSetup = SeatSetup(),
                )
            val after = engine.advance(start, playLandDecision(windowOf(start), "Fixture Mountain"))
            after.pausedState.turn.landsPlayedThisTurn shouldBe 1
            hasPlayLand(after.pending()).shouldBeFalse()
        }

        "CR 305.2: the land drop is available again the next own turn" {
            val start =
                fixtureState(
                    aliceSetup = SeatSetup(hand = listOf("Fixture Mountain", "Fixture Forest")),
                    bobSetup = SeatSetup(),
                )
            var current = engine.advance(start, playLandDecision(windowOf(start), "Fixture Mountain"))
            // Pass everything until alice's next turn's precombat main (turn 5 — turn 4 is bob's).
            var guard = 0
            while (true) {
                val paused = current.shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
                val turn = paused.state.turn
                val window = paused.request as? DecisionRequest.ChooseAction
                if (turn.number == 5 && turn.phase == TurnPhase.PRECOMBAT_MAIN && window?.seat == alice) break
                check(guard++ < 500) { "did not reach alice's next main phase" }
                current = engine.advance(paused.state, respondTo(paused.request))
            }
            val window = current.pending<DecisionRequest.ChooseAction>()
            hasPlayLand(window).shouldBeTrue()
            current.pausedState.turn.landsPlayedThisTurn shouldBe 0
        }

        "CR 305.1: playing a land is not enumerated on another player's turn" {
            val offTurn =
                fixtureState(
                    aliceSetup = SeatSetup(hand = listOf("Fixture Mountain")),
                    bobSetup = SeatSetup(),
                    turn = Turn(bob, 4, TurnPhase.PRECOMBAT_MAIN, null),
                )
            hasPlayLand(windowOf(offTurn)).shouldBeFalse()
        }

        "CR 305.1: playing a land is not enumerated outside a main phase" {
            val upkeep =
                fixtureState(
                    aliceSetup = SeatSetup(hand = listOf("Fixture Mountain")),
                    bobSetup = SeatSetup(),
                    turn = Turn(alice, 3, TurnPhase.BEGINNING, TurnStep.UPKEEP),
                )
            hasPlayLand(windowOf(upkeep)).shouldBeFalse()
            val postcombat =
                fixtureState(
                    aliceSetup = SeatSetup(hand = listOf("Fixture Mountain")),
                    bobSetup = SeatSetup(),
                    turn = Turn(alice, 3, TurnPhase.POSTCOMBAT_MAIN, null),
                )
            hasPlayLand(windowOf(postcombat)).shouldBeTrue()
        }

        "CR 116.2a: playing a land is not enumerated while the stack is nonempty" {
            val start =
                fixtureState(
                    aliceSetup =
                        SeatSetup(
                            hand = listOf("Fixture Bolt", "Fixture Mountain"),
                            battlefield = listOf("Fixture Mountain"),
                        ),
                    bobSetup = SeatSetup(),
                )
            hasPlayLand(windowOf(start)).shouldBeTrue()
            var current = engine.advance(start, castDecision(windowOf(start), "Fixture Bolt"))
            current = engine.advance(current.pausedState, targetDecision(current.pending(), bob))
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            // The caster's post-cast window: the stack now holds the Bolt (CR 116.2a bars the play).
            current.pausedState.sharedZones.stack shouldHaveSize 1
            hasPlayLand(current.pending()).shouldBeFalse()
            // Both players pass; the Bolt resolves; the stack empties and the same main phase's
            // land drop — never used — is enumerable again.
            current = engine.advance(current.pausedState, passDecision(current.pending()))
            current = engine.advance(current.pausedState, passDecision(current.pending()))
            current.pausedState.sharedZones.stack
                .shouldBeEmpty()
            current.pausedState.turn.phase shouldBe TurnPhase.PRECOMBAT_MAIN
            hasPlayLand(current.pending()).shouldBeTrue()
        }

        "ADR-005: an undefined land card is inert — no play-land option without a definition" {
            val inert =
                fixtureState(
                    // "Mountain" has no definition in the fixture registry, so it stays inert.
                    aliceSetup = SeatSetup(hand = listOf("Mountain")),
                    bobSetup = SeatSetup(),
                )
            hasPlayLand(windowOf(inert)).shouldBeFalse()
        }
    })
