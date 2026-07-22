package dev.mtgplay.rules

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * CR 508–509 declarations on handcrafted battlefields: attacker eligibility, block legality,
 * blocker ordering, the empty declarations, and CR 508.8's skip — with enumeration completeness
 * asserted in both directions (every legal option present, nothing illegal).
 */
class CombatDeclarationSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 508.1a: an untapped, non-summoning-sick creature is an eligible attacker" {
            val state = attackStep(aliceField = listOf(Combatant("Bear")))
            pausedRequestOf<DecisionRequest.DeclareAttackers>(state).attackerNames() shouldBe listOf("Bear")
        }

        "CR 302.6 and CR 508.1a: a summoning-sick creature is not an eligible attacker" {
            val state = attackStep(aliceField = listOf(Combatant("Ogre"), Combatant("Bear", summoningSick = true)))
            pausedRequestOf<DecisionRequest.DeclareAttackers>(state).attackerNames() shouldBe listOf("Ogre")
        }

        "CR 508.1a: a tapped creature is not an eligible attacker" {
            val state = attackStep(aliceField = listOf(Combatant("Ogre"), Combatant("Bear", tapped = true)))
            pausedRequestOf<DecisionRequest.DeclareAttackers>(state).attackerNames() shouldBe listOf("Ogre")
        }

        "CR 508.1a: attacker enumeration is exactly the eligible attackers (completeness, both directions)" {
            val state =
                attackStep(
                    aliceField =
                        listOf(
                            Combatant("Ogre"),
                            Combatant("Bear", summoningSick = true),
                            Combatant("Giant", tapped = true),
                        ),
                    // The opponent's creature is never an attacker option for the active player.
                    bobField = listOf(Combatant("Wall")),
                )
            pausedRequestOf<DecisionRequest.DeclareAttackers>(state).attackerNames() shouldBe listOf("Ogre")
        }

        "CR 302.6: a controller's turn beginning clears their creatures' summoning sickness" {
            // Bob's turn 4; alice controls a summoning-sick Bear. Driving into alice's next turn
            // runs the turn-begin reset, after which the Bear is no longer sick.
            val start =
                handcraftedCombat(
                    turn = Turn(bob, 4, TurnPhase.ENDING, TurnStep.END),
                    aliceField = listOf(Combatant("Bear", summoningSick = true)),
                    holder = bob,
                )
            val begun =
                engine.driveByPassing(AdvanceResult.NeedsDecision(start, pausedRequestOf(start))) {
                    it.turn.activePlayer == alice && it.turn.number == 5
                }
            begun.pausedState.creature("Bear").summoningSick shouldBe false
        }

        "CR 508.1f and CR 702.21b: declaring an attacker taps it, unless it has vigilance" {
            val state = attackStep(aliceField = listOf(Combatant("Bear"), Combatant("Sentinel")))
            val request = pausedRequestOf<DecisionRequest.DeclareAttackers>(state)
            val after = engine.advance(state, request.declaring("Bear", "Sentinel")).pausedState
            after.creature("Bear").tapped shouldBe true
            after.creature("Sentinel").tapped shouldBe false
        }

        "CR 508.1 and CR 508.8: declaring no attackers is legal and skips declare-blockers and combat-damage" {
            val state = attackStep(aliceField = listOf(Combatant("Bear")))
            val request = pausedRequestOf<DecisionRequest.DeclareAttackers>(state)
            val afterDeclare = engine.advance(state, request.declaring())
            checkNotNull(afterDeclare.pausedState.turn.combat).attackers.shouldBeEmpty()

            val afterRound = engine.passPriorityRound(afterDeclare)
            afterRound.pausedState.turn.step shouldBe TurnStep.END_OF_COMBAT
            val steps =
                afterRound.pausedState.events
                    .filterIsInstance<GameEvent.StepBegan>()
                    .map { it.step }
            steps shouldNotContain TurnStep.DECLARE_BLOCKERS
            steps shouldNotContain TurnStep.COMBAT_DAMAGE
        }

        "CR 509.1a: an untapped defending creature can block, a tapped one cannot" {
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Giant")),
                    bobField = listOf(Combatant("Bear"), Combatant("Ogre", tapped = true)),
                )
            val blockers = engine.toDeclareBlockers(state, "Giant").pending<DecisionRequest.DeclareBlockers>()
            blockers.blockPairs() shouldBe listOf("Bear" to "Giant")
        }

        "CR 509.1b: a flying attacker can be blocked only by a creature with flying" {
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Flyer")),
                    bobField = listOf(Combatant("Bear"), Combatant("Raptor")),
                )
            val blockers = engine.toDeclareBlockers(state, "Flyer").pending<DecisionRequest.DeclareBlockers>()
            blockers.blockPairs() shouldBe listOf("Raptor" to "Flyer")
        }

        "CR 509.1b: a non-flying attacker can be blocked by grounded or flying creatures (completeness)" {
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Bear")),
                    bobField = listOf(Combatant("Ogre"), Combatant("Raptor")),
                )
            val blockers = engine.toDeclareBlockers(state, "Bear").pending<DecisionRequest.DeclareBlockers>()
            blockers.blockPairs() shouldBe listOf("Ogre" to "Bear", "Raptor" to "Bear")
        }

        "CR 509.1: declaring no blockers is legal and combat proceeds to priority" {
            val state = attackStep(aliceField = listOf(Combatant("Giant")), bobField = listOf(Combatant("Bear")))
            val toBlock = engine.toDeclareBlockers(state, "Giant")
            val afterBlocks = engine.declareBlocks(toBlock)
            checkNotNull(afterBlocks.pausedState.turn.combat).blocks.shouldBeEmpty()
            afterBlocks.pending<DecisionRequest.ChooseAction>()
        }

        "CR 509.2: an attacker blocked by two creatures gets a blocker-order decision" {
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Giant")),
                    bobField = listOf(Combatant("Bear"), Combatant("Ogre")),
                )
            val toBlock = engine.toDeclareBlockers(state, "Giant")
            val afterBlocks = engine.declareBlocks(toBlock, "Bear" to "Giant", "Ogre" to "Giant")
            val order = afterBlocks.pending<DecisionRequest.OrderBlockers>()
            order.attacker shouldBe afterBlocks.pausedState.creature("Giant").id
            order.options.map { it.card.name } shouldContainExactlyInAnyOrder listOf("Bear", "Ogre")
        }

        "CR 509.2: an attacker blocked by only one creature is not ordered" {
            val state = attackStep(aliceField = listOf(Combatant("Giant")), bobField = listOf(Combatant("Bear")))
            val toBlock = engine.toDeclareBlockers(state, "Giant")
            val afterBlocks = engine.declareBlocks(toBlock, "Bear" to "Giant")
            afterBlocks.pending<DecisionRequest.ChooseAction>()
        }
    })
