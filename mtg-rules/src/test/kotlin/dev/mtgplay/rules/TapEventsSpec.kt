package dev.mtgplay.rules

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.untapStepTurnBasedActions
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.toPersistentList

/**
 * The tap and untap events (P2.2, additive on `GameEvent`): [GameEvent.ObjectTapped] narrates
 * the `{T}` cost of a mana ability (CR 605.1a), and [GameEvent.ObjectUntapped] narrates each
 * object the untap step's turn-based action untaps (CR 502.2).
 */
class TapEventsSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 605.1a: paying a cost by tapping emits ObjectTapped between the activation and its mana" {
            val start =
                fixtureState(
                    aliceSetup =
                        SeatSetup(hand = listOf("Fixture Bolt"), battlefield = listOf("Fixture Mountain")),
                    bobSetup = SeatSetup(),
                )
            val sourceId =
                start.sharedZones.battlefield
                    .single()
                    .id
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(start)
            var current = engine.advance(start, castDecision(window, "Fixture Bolt"))
            current = engine.advance(current.pausedState, targetDecision(current.pending(), bob))
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            val names =
                current.pausedState.events
                    .map { it::class.simpleName }
                    .filter { it in setOf("ManaAbilityActivated", "ObjectTapped", "ManaAdded") }
            names shouldBe listOf("ManaAbilityActivated", "ObjectTapped", "ManaAdded")
            current.pausedState.events.filterIsInstance<GameEvent.ObjectTapped>() shouldBe
                listOf(
                    GameEvent.ObjectTapped(
                        sourceId,
                        start.sharedZones.battlefield
                            .single()
                            .card,
                    ),
                )
        }

        "CR 502.2: the untap step untaps only the active player's tapped permanents, one ObjectUntapped each" {
            val base =
                fixtureState(
                    aliceSetup = SeatSetup(battlefield = listOf("Fixture Mountain", "Fixture Forest")),
                    bobSetup = SeatSetup(battlefield = listOf("Fixture Island")),
                )
            // Tap everything, then perform the untap step's turn-based action for alice's turn.
            val allTapped =
                base.copy(
                    sharedZones =
                        base.sharedZones.copy(
                            battlefield =
                                base.sharedZones.battlefield
                                    .map { it.copy(tapped = true) }
                                    .toPersistentList(),
                        ),
                )
            val untapped = untapStepTurnBasedActions(allTapped)
            val aliceObjects = untapped.sharedZones.battlefield.filter { it.owner == alice }
            val bobObjects = untapped.sharedZones.battlefield.filter { it.owner == bob }
            aliceObjects.all { !it.tapped }.shouldBeTrue()
            // CR 502.2: only the active player untaps; bob's permanent stays tapped.
            bobObjects.single().tapped.shouldBeTrue()
            untapped.events.filterIsInstance<GameEvent.ObjectUntapped>() shouldBe
                aliceObjects.map { GameEvent.ObjectUntapped(it.id, it.card) }
        }

        "CR 502.2: an untapped battlefield emits no ObjectUntapped events" {
            val base =
                fixtureState(
                    aliceSetup = SeatSetup(battlefield = listOf("Fixture Mountain")),
                    bobSetup = SeatSetup(),
                )
            val untapped = untapStepTurnBasedActions(base)
            untapped.events.any { it is GameEvent.ObjectUntapped }.shouldBeFalse()
        }
    })
