package dev.mtgplay.rules

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

/**
 * CR 510 combat damage on handcrafted battlefields, with P3.2 lethality: the deterministic
 * CR-minimum assignment (P3.2 still has no assignment decision), marked damage vs life loss, the
 * damage-assignment order, the first-strike two-step — and now the CR 704.5g state-based action
 * that destroys a creature dealt lethal combat damage. A creature dealt lethal damage is in its
 * owner's graveyard by the time the post-damage priority window opens (the state-based action runs
 * at the priority grant that follows the damage step, CR 704.3); a creature that survives keeps its
 * marked damage until cleanup (CR 514.2).
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
            // The unharmed attacker is still on the battlefield — no state-based action applied.
            afterDamage.creature("Giant").damageMarked shouldBe 0
        }

        "CR 510.1d and CR 120.3d: sublethal combat damage is marked on the blocker, not lost as life, no death" {
            // Ogre (3 power) into Wall (0/4): the Wall takes 3 marked (3 < 4, survives), and deals 0
            // back. Nobody dies, so this isolates the marked-damage-is-not-life-loss property.
            val state = attackStep(aliceField = listOf(Combatant("Ogre")), bobField = listOf(Combatant("Wall")))
            val afterBlocks = engine.declareBlocks(engine.toDeclareBlockers(state, "Ogre"), "Wall" to "Ogre")
            val afterDamage = engine.passPriorityRound(afterBlocks).pausedState

            afterDamage.creature("Wall").damageMarked shouldBe 3 // Ogre's power, sublethal to toughness 4
            afterDamage.creature("Ogre").damageMarked shouldBe 0 // the Wall's 0 power marks nothing
            // CR 120.3d: object damage is marked, not lost as life — no LifeChanged anywhere.
            afterDamage.players.getValue(alice).life shouldBe STARTING_LIFE
            afterDamage.players.getValue(bob).life shouldBe STARTING_LIFE
            afterDamage.events.filterIsInstance<GameEvent.LifeChanged>().shouldBeEmpty()
        }

        "CR 704.5g and CR 510.2: two creatures dealt simultaneous lethal combat damage die together" {
            // Ogre (3/3) attacks, Raptor (3/3) blocks: each marks 3 on the other simultaneously
            // (CR 510.2), both reach lethal (CR 704.5g), so both are in their owners' graveyards
            // when the post-damage priority window opens.
            val state = attackStep(aliceField = listOf(Combatant("Ogre")), bobField = listOf(Combatant("Raptor")))
            val afterBlocks = engine.declareBlocks(engine.toDeclareBlockers(state, "Ogre"), "Raptor" to "Ogre")
            val afterDamage = engine.passPriorityRound(afterBlocks).pausedState

            afterDamage.sharedZones.battlefield
                .filter { it.card == CardRef("Ogre") || it.card == CardRef("Raptor") }
                .shouldBeEmpty()
            afterDamage.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldBe listOf(CardRef("Ogre"))
            afterDamage.players
                .getValue(bob)
                .graveyard
                .map { it.card } shouldBe listOf(CardRef("Raptor"))
            // CR 700.4: each death is one CreatureDied event; no player took damage.
            afterDamage.events.filterIsInstance<GameEvent.CreatureDied>().map { it.card } shouldContainExactlyInAnyOrder
                listOf(CardRef("Ogre"), CardRef("Raptor"))
            afterDamage.events.filterIsInstance<GameEvent.LifeChanged>().shouldBeEmpty()
        }

        "CR 704.5g leaves combat coherent: the dead attacker and blocker no longer appear in combat" {
            val state = attackStep(aliceField = listOf(Combatant("Ogre")), bobField = listOf(Combatant("Raptor")))
            val afterBlocks = engine.declareBlocks(engine.toDeclareBlockers(state, "Ogre"), "Raptor" to "Ogre")
            val afterDamage = engine.passPriorityRound(afterBlocks).pausedState

            // CR 506.4: a permanent that leaves the battlefield leaves combat. Both did.
            val combat = checkNotNull(afterDamage.turn.combat) { "combat is still in progress until end of combat" }
            combat.attackers.shouldBeEmpty()
            combat.blocks.orEmpty().shouldBeEmpty()
        }

        "CR 509.2 and CR 510.1c: the damage-assignment order determines which blocker is dealt lethal" {
            // Behemoth (power 4) blocked by Bear (2/2) and Ogre (3/3). Order [Bear, Ogre]: Bear takes
            // lethal 2 and dies; the remaining 2 goes to Ogre, which is sublethal (2 < 3) — Ogre
            // lives with 2 marked. Behemoth survives (takes 2 + 3 = 5 < 8).
            val bearFirst = engine.driveBlockedBehemoth(order = arrayOf("Bear", "Ogre"))
            bearFirst.sharedZones.battlefield.none { it.card == CardRef("Bear") } shouldBe true
            bearFirst.creature("Ogre").damageMarked shouldBe 2
            bearFirst.creature("Behemoth").damageMarked shouldBe 5

            // Reversed order [Ogre, Bear]: Ogre takes lethal 3 and dies; the remaining 1 goes to
            // Bear, sublethal (1 < 2) — Bear lives with 1 marked. The order flipped which one died.
            val ogreFirst = engine.driveBlockedBehemoth(order = arrayOf("Ogre", "Bear"))
            ogreFirst.sharedZones.battlefield.none { it.card == CardRef("Ogre") } shouldBe true
            ogreFirst.creature("Bear").damageMarked shouldBe 1
            ogreFirst.creature("Behemoth").damageMarked shouldBe 5
        }

        "CR 510.5 and CR 704.5g: a first-striker kills its blocker in the first step before it can deal back" {
            val state = attackStep(aliceField = listOf(Combatant("Striker")), bobField = listOf(Combatant("Bear")))
            val afterBlocks = engine.declareBlocks(engine.toDeclareBlockers(state, "Striker"), "Bear" to "Striker")

            // First combat-damage step: only the first-striker (Striker) deals — 2 to the Bear,
            // which is lethal, so the Bear dies at the state-based-action check before the window.
            val afterFirstStrike = engine.passPriorityRound(afterBlocks).pausedState
            afterFirstStrike.sharedZones.battlefield.none { it.card == CardRef("Bear") } shouldBe true
            afterFirstStrike.creature("Striker").damageMarked shouldBe 0

            // Second combat-damage step: the Bear is already dead, so it never deals its damage —
            // the whole point of first strike. Striker comes through the combat unharmed.
            val atSecondStep = AdvanceResult.NeedsDecision(afterFirstStrike, pausedRequestOf(afterFirstStrike))
            val afterRegular = engine.passPriorityRound(atSecondStep).pausedState
            afterRegular.creature("Striker").damageMarked shouldBe 0
            val damageSteps = afterRegular.events.filterIsInstance<GameEvent.StepBegan>()
            damageSteps.count { it.step == TurnStep.COMBAT_DAMAGE } shouldBe 2
        }

        "CR 514.2: sublethal marked damage wears off as the cleanup step ends" {
            val start =
                handcraftedCombat(
                    turn = Turn(alice, TURN_NUMBER, TurnPhase.ENDING, TurnStep.END),
                    aliceField = listOf(Combatant("Bear", damageMarked = 1)),
                    holder = alice,
                )
            val next =
                engine.driveByPassing(AdvanceResult.NeedsDecision(start, pausedRequestOf(start))) {
                    it.turn.number == TURN_NUMBER + 1
                }
            next.pausedState.creature("Bear").damageMarked shouldBe 0
        }

        "ADR-006: an identically driven combat reaches an identical state (replay through combat)" {
            engine.driveBlockedBehemoth(order = arrayOf("Bear", "Ogre")) shouldBe
                engine.driveBlockedBehemoth(order = arrayOf("Bear", "Ogre"))
        }
    })

// Drives Behemoth vs Bear + Ogre through declaration, two-blocker ordering, and the combat-damage
// step from a fresh handcrafted state, returning the post-damage state. [order] names the blockers
// in damage-assignment order. Behemoth (4/8) survives the gang, so which blocker its assignment
// killed stays observable afterward.
private fun DefaultGameEngine.driveBlockedBehemoth(order: Array<String>): GameState {
    val state =
        attackStep(
            aliceField = listOf(Combatant("Behemoth")),
            bobField = listOf(Combatant("Bear"), Combatant("Ogre")),
        )
    val toBlock = toDeclareBlockers(state, "Behemoth")
    val afterBlocks = declareBlocks(toBlock, "Bear" to "Behemoth", "Ogre" to "Behemoth")
    val orderReq = afterBlocks.pending<DecisionRequest.OrderBlockers>()
    val afterOrder = advance(afterBlocks.pausedState, orderReq.ordering(*order))
    return passPriorityRound(afterOrder).pausedState
}
