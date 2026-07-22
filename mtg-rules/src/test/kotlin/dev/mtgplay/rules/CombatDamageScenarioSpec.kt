package dev.mtgplay.rules

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

/**
 * CR 510 combat damage on handcrafted battlefields: the deterministic CR-minimum assignment
 * (P3.1 has no assignment decision), marked damage vs life loss, damage-assignment order, and the
 * first-strike two-step. Nothing dies from marked damage in P3.1 — the lethal-damage state-based
 * action is P3.2 — so every combatant is still on the battlefield afterward.
 */
class CombatDamageScenarioSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 510.1c: an unblocked attacker deals its power to the defending player, DamageDealt then LifeChanged" {
            val state = attackStep(aliceField = listOf(Combatant("Giant")))
            val afterBlocks = engine.declareBlocks(engine.toDeclareBlockers(state, "Giant"))
            val afterDamage = engine.passPriorityRound(afterBlocks).pausedState

            afterDamage.players.getValue(bob).life shouldBe STARTING_LIFE - 4
            // CR 120.3a: the player's DamageDealt is immediately followed by its LifeChanged result.
            val events = afterDamage.events
            val index = events.indexOfFirst { it == GameEvent.DamageDealt(Target.Player(bob), 4) }
            index shouldBeGreaterThanOrEqual 0
            events[index + 1] shouldBe GameEvent.LifeChanged(bob, -4, STARTING_LIFE - 4)
        }

        "CR 510.1d and CR 120.3d: a blocked attacker and its blocker mark damage on each other, no life change" {
            val state = attackStep(aliceField = listOf(Combatant("Ogre")), bobField = listOf(Combatant("Bear")))
            val afterBlocks = engine.declareBlocks(engine.toDeclareBlockers(state, "Ogre"), "Bear" to "Ogre")
            val afterDamage = engine.passPriorityRound(afterBlocks).pausedState

            afterDamage.creature("Bear").damageMarked shouldBe 3 // Ogre's power
            afterDamage.creature("Ogre").damageMarked shouldBe 2 // Bear's power
            // CR 120.3d: object damage is marked, not lost as life — no LifeChanged, no death (P3.2).
            afterDamage.players.getValue(alice).life shouldBe STARTING_LIFE
            afterDamage.players.getValue(bob).life shouldBe STARTING_LIFE
            afterDamage.events.filterIsInstance<GameEvent.LifeChanged>().shouldBeEmpty()
        }

        "CR 510.1c: a blocked attacker assigns lethal to each blocker in order, remainder wasted on the last" {
            // Power 6, order [Bear(2), Ogre(3)]: Bear takes lethal 2, Ogre the remaining 4 (1 wasted).
            val afterDamage = engine.driveBlockedColossus(order = arrayOf("Bear", "Ogre"))
            afterDamage.creature("Bear").damageMarked shouldBe 2
            afterDamage.creature("Ogre").damageMarked shouldBe 4
            afterDamage.creature("Colossus").damageMarked shouldBe 5 // 2 + 3 from the two blockers
        }

        "CR 509.2 and CR 510.1c: the damage-assignment order determines which blocker absorbs the remainder" {
            // Reversed order [Ogre(3), Bear(2)]: Ogre takes lethal 3, Bear the remaining 3 (1 wasted).
            val afterDamage = engine.driveBlockedColossus(order = arrayOf("Ogre", "Bear"))
            afterDamage.creature("Ogre").damageMarked shouldBe 3
            afterDamage.creature("Bear").damageMarked shouldBe 3
        }

        "CR 510.5: first strike splits combat into two damage steps; the first-striker deals first" {
            val state = attackStep(aliceField = listOf(Combatant("Striker")), bobField = listOf(Combatant("Bear")))
            val afterBlocks = engine.declareBlocks(engine.toDeclareBlockers(state, "Striker"), "Bear" to "Striker")

            // First combat-damage step: only the first-striker (Striker) deals.
            val afterFirstStrike = engine.passPriorityRound(afterBlocks).pausedState
            afterFirstStrike.creature("Bear").damageMarked shouldBe 2
            afterFirstStrike.creature("Striker").damageMarked shouldBe 0

            // Second combat-damage step: the non-first-striker (Bear) now deals.
            val atSecondStep = AdvanceResult.NeedsDecision(afterFirstStrike, pausedRequestOf(afterFirstStrike))
            val afterRegular = engine.passPriorityRound(atSecondStep).pausedState
            afterRegular.creature("Striker").damageMarked shouldBe 2
            afterRegular.creature("Bear").damageMarked shouldBe 2
            val damageSteps = afterRegular.events.filterIsInstance<GameEvent.StepBegan>()
            damageSteps.count { it.step == TurnStep.COMBAT_DAMAGE } shouldBe 2
        }

        "CR 514.2: marked damage wears off as the cleanup step ends" {
            val start =
                handcraftedCombat(
                    turn = Turn(alice, TURN_NUMBER, TurnPhase.ENDING, TurnStep.END),
                    aliceField = listOf(Combatant("Bear", damageMarked = 2)),
                    holder = alice,
                )
            val next =
                engine.driveByPassing(AdvanceResult.NeedsDecision(start, pausedRequestOf(start))) {
                    it.turn.number == TURN_NUMBER + 1
                }
            next.pausedState.creature("Bear").damageMarked shouldBe 0
        }

        "ADR-006: an identically driven combat reaches an identical state (replay through combat)" {
            engine.driveBlockedColossus(order = arrayOf("Bear", "Ogre")) shouldBe
                engine.driveBlockedColossus(order = arrayOf("Bear", "Ogre"))
        }
    })

// Drives Colossus vs Bear+Ogre through declaration, two-blocker ordering, and the combat-damage
// step from a fresh handcrafted state, returning the post-damage state. [order] names the blockers
// in damage-assignment order.
private fun DefaultGameEngine.driveBlockedColossus(order: Array<String>): GameState {
    val state =
        attackStep(
            aliceField = listOf(Combatant("Colossus")),
            bobField = listOf(Combatant("Bear"), Combatant("Ogre")),
        )
    val toBlock = toDeclareBlockers(state, "Colossus")
    val afterBlocks = declareBlocks(toBlock, "Bear" to "Colossus", "Ogre" to "Colossus")
    val orderReq = afterBlocks.pending<DecisionRequest.OrderBlockers>()
    val afterOrder = advance(afterBlocks.pausedState, orderReq.ordering(*order))
    return passPriorityRound(afterOrder).pausedState
}
