package dev.mtgplay.rules

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Lifelink as a result of damage (CR 702.15), *not* a triggered ability. Damage a lifelink source
 * deals also causes its controller to gain that much life in the same atomic transition — no stack,
 * no response window. Zero damage gains nothing (CR 702.15f). The distinctness scenario pins the
 * difference from Armadillo Cloak's "whenever enchanted creature deals damage" trigger: a creature
 * with *granted* lifelink and enchanted by a Cloak analogue yields one immediate lifelink gain plus a
 * separate trigger on the stack for the same one damage event.
 */
class LifelinkScenarioSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        // Drives [attacker] (alice's) into an unblocked swing at bob, returning the post-damage pause.
        fun swingUnblocked(attacker: String): AdvanceResult {
            val state = attackStep(aliceField = listOf(Combatant(attacker)))
            val afterBlocks = engine.declareBlocks(engine.toDeclareBlockers(state, attacker))
            return engine.passPriorityRound(afterBlocks)
        }

        "CR 702.15: a lifelink creature's combat damage gains its controller that much life, in the same transition" {
            val after = swingUnblocked("Lifelinker").pausedState
            // Lifelinker (3/3) dealt 3 to bob; alice gained 3 as a result of that damage.
            after.players.getValue(bob).life shouldBe STARTING_LIFE - 3
            after.players.getValue(alice).life shouldBe STARTING_LIFE + 3
            // Lifelink is no trigger: nothing was put on the stack and nothing waits to be.
            after.sharedZones.stack
                .filterIsInstance<StackEntry.Ability>()
                .shouldBeEmpty()
            after.pendingTriggers.shouldBeEmpty()
        }

        "CR 702.15f: lifelink on zero damage gains nothing — no damage was dealt" {
            val after = swingUnblocked("Meek").pausedState
            // Meek (0/1) dealt 0 combat damage: neither life total moved.
            after.players.getValue(bob).life shouldBe STARTING_LIFE
            after.players.getValue(alice).life shouldBe STARTING_LIFE
        }

        "CR 702.15 vs 603.2: granted lifelink and an Armadillo-Cloak trigger are distinct for one damage event" {
            // alice's Ogre (3/3) carries granted lifelink (Vamp Aura) AND a Cloak analogue (Bloodfeast,
            // "whenever enchanted creature deals damage, you gain that much life").
            val state =
                keywordState(
                    battlefield =
                        listOf(
                            combatObject(0, "Ogre", alice),
                            combatObject(1, "Vamp Aura", alice, attachedTo = 0),
                            combatObject(2, "Bloodfeast", alice, attachedTo = 0),
                        ),
                    turn = Turn(alice, TURN_NUMBER, TurnPhase.COMBAT, TurnStep.DECLARE_ATTACKERS),
                )
            val afterBlocks = engine.declareBlocks(engine.toDeclareBlockers(state, "Ogre"))
            val afterDamage = engine.passPriorityRound(afterBlocks)

            // Immediately after the damage step: lifelink has already gained 3 (a result of the
            // damage), while the Cloak trigger is only now on the stack, unresolved.
            afterDamage.pausedState.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE + 3
            afterDamage.pausedState.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - 3
            afterDamage.pausedState.sharedZones.stack
                .filterIsInstance<StackEntry.Ability>()
                .size shouldBe 1

            // Resolving the Cloak trigger gains another 3: one damage event, two distinct gains.
            val afterTrigger = engine.passPriorityRound(afterDamage).pausedState
            afterTrigger.players.getValue(alice).life shouldBe STARTING_LIFE + 6
        }

        "CR 702.15 and 702.19e: a trampler with granted lifelink gains the total it dealt across the split" {
            // alice's Trampler (5/5, trample) carries granted lifelink (Vamp Aura), blocked by bob's Bear.
            val state =
                keywordState(
                    battlefield =
                        listOf(
                            combatObject(0, "Trampler", alice),
                            combatObject(1, "Vamp Aura", alice, attachedTo = 0),
                            combatObject(2, "Bear", bob),
                        ),
                    turn = Turn(alice, TURN_NUMBER, TurnPhase.COMBAT, TurnStep.DECLARE_ATTACKERS),
                )
            val afterBlocks = engine.declareBlocks(engine.toDeclareBlockers(state, "Trampler"), "Bear" to "Trampler")
            val choice = engine.passPriorityRound(afterBlocks)
            val req = choice.pending<DecisionRequest.AssignTrampleDamage>()
            // Excess is 5 - 2 = 3; assign 2 to the player, leaving 3 on the Bear (lethal 2 + 1 overkill).
            val after = engine.advance(choice.pausedState, Decision.SingleSelect(req.id, 2)).pausedState

            after.players.getValue(bob).life shouldBe STARTING_LIFE - 2
            after.sharedZones.battlefield.none { it.card == CardRef("Bear") } shouldBe true
            // Lifelink gains the Trampler's *total* combat damage this event: 3 to the Bear + 2 to bob = 5.
            after.players.getValue(alice).life shouldBe STARTING_LIFE + 5
        }
    })
