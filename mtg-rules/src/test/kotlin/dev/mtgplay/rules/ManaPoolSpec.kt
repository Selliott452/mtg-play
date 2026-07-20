package dev.mtgplay.rules

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.untapStepTurnBasedActions
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/** Mana abilities (CR 605), pool emptying (CR 500.4), and the untap step (CR 502.2). */
class ManaPoolSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 605.3: a mana ability resolves within the cast — no priority round, no extra decision" {
            val start =
                fixtureState(
                    aliceSetup = SeatSetup(hand = listOf("Fixture Bolt"), battlefield = listOf("Fixture Mountain")),
                    bobSetup = SeatSetup(),
                )
            var current = engine.advance(start, castDecision(pausedRequestOf(start), "Fixture Bolt"))
            current = engine.advance(current.pausedState, targetDecision(current.pending(), bob))
            // One advance takes the payment choice all the way to the post-cast window: the tap,
            // the mana, and the payment happened inside it, with no pause in between (CR 605.3a-b).
            val afterCast = engine.advance(current.pausedState, planDecision(current.pending()))
            afterCast.pending<DecisionRequest.ChooseAction>().seat shouldBe alice
            val events = afterCast.pausedState.events
            events.filterIsInstance<GameEvent.ManaAbilityActivated>() shouldHaveSize 1
            events.filterIsInstance<GameEvent.ManaAdded>() shouldHaveSize 1
        }

        "CR 500.4: a pool that survives to the end of a step empties, emitting ManaPoolEmptied" {
            // Handcrafted: alice holds a floating red mana at an upkeep window. Unreachable
            // through P2.1 casts (payment is exact), so the emptying rule is driven directly.
            val base =
                fixtureState(
                    aliceSetup = SeatSetup(),
                    bobSetup = SeatSetup(),
                    turn = Turn(alice, 3, TurnPhase.BEGINNING, TurnStep.UPKEEP),
                )
            val floating =
                base.copy(
                    players =
                        base.players.putting(
                            alice,
                            base.players.getValue(alice).copy(manaPool = persistentListOf(ManaType.RED)),
                        ),
                )
            // Both players pass: the upkeep step ends and the pool must empty.
            var current = engine.advance(floating, passDecisionFor(floating, alice))
            val bobWindow = current.pending<DecisionRequest.ChooseAction>()
            current = engine.advance(current.pausedState, passDecision(bobWindow))
            val next = current.pausedState
            next.players
                .getValue(alice)
                .manaPool
                .shouldBeEmpty()
            next.events.filterIsInstance<GameEvent.ManaPoolEmptied>() shouldHaveSize 1
        }

        "CR 502.2: the untap step untaps the active player's permanents and no one else's" {
            val base =
                fixtureState(
                    aliceSetup = SeatSetup(battlefield = listOf("Fixture Mountain")),
                    bobSetup = SeatSetup(battlefield = listOf("Fixture Mountain")),
                    turn = Turn(alice, 4, TurnPhase.BEGINNING, TurnStep.UNTAP),
                )
            val bothTapped =
                base.copy(
                    sharedZones =
                        base.sharedZones.copy(
                            battlefield =
                                base.sharedZones.battlefield
                                    .map { it.copy(tapped = true) }
                                    .toPersistentList(),
                        ),
                )
            val untapped = untapStepTurnBasedActions(bothTapped)
            val aliceMountain = untapped.sharedZones.battlefield.first { it.owner == alice }
            val bobMountain = untapped.sharedZones.battlefield.first { it.owner == bob }
            aliceMountain.tapped.shouldBeFalse()
            bobMountain.tapped.shouldBeTrue()
        }

        "a tapped source recovers across its controller's untap step and is castable-from again" {
            val start =
                fixtureState(
                    aliceSetup =
                        SeatSetup(
                            hand = listOf("Fixture Bolt", "Fixture Bolt"),
                            battlefield = listOf("Fixture Mountain"),
                        ),
                    bobSetup = SeatSetup(),
                    turn = Turn(alice, 3, TurnPhase.POSTCOMBAT_MAIN, null),
                )
            // Cast the first Bolt (taps the Mountain), resolve it, then pass to alice's next untap.
            var current = engine.advance(start, castDecision(pausedRequestOf(start), "Fixture Bolt"))
            current = engine.advance(current.pausedState, targetDecision(current.pending(), bob))
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            current = engine.advance(current.pausedState, passDecision(current.pending()))
            current = engine.advance(current.pausedState, passDecision(current.pending()))
            current.pausedState.sharedZones.battlefield
                .single()
                .tapped
                .shouldBeTrue()

            // Pass through to alice's next turn: her untap step untaps the Mountain, so the
            // second Bolt is affordable and enumerated again.
            var steps = 0
            while (!(
                    current.pausedState.turn.activePlayer == alice &&
                        current.pausedState.turn.number == 5 &&
                        current.pausedState.turn.phase == TurnPhase.PRECOMBAT_MAIN
                )
            ) {
                check(steps++ < 200) { "did not reach alice's next main phase" }
                val request = current.pending<DecisionRequest.ChooseAction>()
                current = engine.advance(current.pausedState, passDecision(request))
            }
            current.pausedState.sharedZones.battlefield
                .single()
                .tapped
                .shouldBeFalse()
            enumeratedCasts(current.pending()) shouldBe listOf("Fixture Bolt")
        }
    })
