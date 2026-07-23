package dev.mtgplay.rules

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.AttackerAssignment
import dev.mtgplay.core.state.CombatState
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.combatDamageStep
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * Trample combat-damage assignment (CR 702.19). A blocked trampler assigns at least lethal to each
 * surviving blocker (CR 510.1c) and its controller may assign the above-lethal excess to the
 * defending player (CR 702.19e) — the [DecisionRequest.AssignTrampleDamage] choice; the unassigned
 * remainder overkills a blocker (outcome-irrelevant). A blocked trampler whose blockers all left
 * combat assigns all its damage to the player (CR 702.19g), a blocked non-trampler none (CR 510.1c).
 */
class TrampleScenarioSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        // Drives Trampler (5/5) into Bear (2/2) to the trample-assignment pause. Excess is 5 - 2 = 3.
        fun toTrampleChoice(): AdvanceResult {
            val state = attackStep(aliceField = listOf(Combatant("Trampler")), bobField = listOf(Combatant("Bear")))
            val afterBlocks = engine.declareBlocks(engine.toDeclareBlockers(state, "Trampler"), "Bear" to "Trampler")
            return engine.passPriorityRound(afterBlocks)
        }

        "CR 702.19e: the trample assignment enumerates every amount 0..excess to the defending player" {
            val req = toTrampleChoice().pending<DecisionRequest.AssignTrampleDamage>()
            // Trampler power 5, Bear lethal 2 → excess 3, so the player may be given 0, 1, 2, or 3.
            req.options shouldBe listOf(0, 1, 2, 3)
            req.attackerCard shouldBe CardRef("Trampler")
            req.defendingPlayer shouldBe bob
        }

        "CR 702.19e: assigning the whole excess still leaves each blocker exactly its lethal — never less" {
            val choice = toTrampleChoice()
            val req = choice.pending<DecisionRequest.AssignTrampleDamage>()
            // Assign the maximum, 3, to the player: the Bear must still receive its full lethal (2).
            val after = engine.advance(choice.pausedState, Decision.SingleSelect(req.id, 3)).pausedState

            after.players.getValue(bob).life shouldBe STARTING_LIFE - 3
            // The Bear took exactly lethal (2 = its toughness) and died at the state-based action.
            after.sharedZones.battlefield.none { it.card == CardRef("Bear") } shouldBe true
            // The Trampler took the Bear's 2 back, survives (2 < 5).
            after.creature("Trampler").damageMarked shouldBe 2
        }

        "CR 702.19e: assigning zero to the player dumps the whole hit on the blocker, none to the player" {
            val choice = toTrampleChoice()
            val req = choice.pending<DecisionRequest.AssignTrampleDamage>()
            val after = engine.advance(choice.pausedState, Decision.SingleSelect(req.id, 0)).pausedState

            // Player untouched; the Bear absorbed all 5 (lethal 2 plus 3 wasted overkill) and died.
            after.players.getValue(bob).life shouldBe STARTING_LIFE
            after.sharedZones.battlefield.none { it.card == CardRef("Bear") } shouldBe true
        }

        "CR 702.19g: a blocked trampler whose blockers all left combat assigns all its damage to the player" {
            val after = engine.dealBlockedNoSurvivors("Trampler")
            // Trampler is blocked (CR 509.1h) but has no surviving blocker: its full 5 goes to the player.
            after.players.getValue(bob).life shouldBe STARTING_LIFE - 5
        }

        "CR 510.1c: a blocked NON-trampler whose blockers all left combat assigns no damage at all" {
            val after = engine.dealBlockedNoSurvivors("Giant")
            // Giant (4/4) has no trample: blocked with no surviving blocker, it deals nothing.
            after.players.getValue(bob).life shouldBe STARTING_LIFE
        }

        "CR 510.5 and 702.19e: a first-strike trampler assigns its excess in the first-strike step" {
            // Charger (4/4, first strike + trample) into Bear (2/2): excess 4 - 2 = 2, chosen in the
            // first-strike step; the Bear dies before it can deal back.
            val state = attackStep(aliceField = listOf(Combatant("Charger")), bobField = listOf(Combatant("Bear")))
            val afterBlocks = engine.declareBlocks(engine.toDeclareBlockers(state, "Charger"), "Bear" to "Charger")
            val choice = engine.passPriorityRound(afterBlocks)
            val req = choice.pending<DecisionRequest.AssignTrampleDamage>()
            req.options shouldBe listOf(0, 1, 2)

            val afterFirstStrike = engine.advance(choice.pausedState, Decision.SingleSelect(req.id, 2)).pausedState
            afterFirstStrike.players.getValue(bob).life shouldBe STARTING_LIFE - 2
            afterFirstStrike.sharedZones.battlefield.none { it.card == CardRef("Bear") } shouldBe true
            // First strike: the Bear died in the first step, so the Charger took nothing back.
            afterFirstStrike.creature("Charger").damageMarked shouldBe 0
        }

        "ADR-006: an identically driven trample combat reaches an identical state (replay)" {
            fun run(): GameState {
                val choice = toTrampleChoice()
                val req = choice.pending<DecisionRequest.AssignTrampleDamage>()
                return engine.advance(choice.pausedState, Decision.SingleSelect(req.id, 2)).pausedState
            }
            run() shouldBe run()
        }
    })

// Deals combat damage for [attackerName] blocked but with every blocker already gone from combat
// (CR 509.1h / 702.19g): a handcrafted combat-damage-step state whose blocked attacker has an empty
// block list. Returns the post-damage paused state.
private fun DefaultGameEngine.dealBlockedNoSurvivors(attackerName: String): GameState {
    val base =
        handcraftedCombat(
            turn = Turn(alice, TURN_NUMBER, TurnPhase.COMBAT, TurnStep.COMBAT_DAMAGE),
            aliceField = listOf(Combatant(attackerName)),
        )
    val attackerId = base.creature(attackerName).id
    val combat =
        CombatState(
            attackers = persistentListOf(AttackerAssignment(attackerId, bob)),
            blocks = persistentListOf(),
            blockedAttackers = persistentSetOf(attackerId),
        )
    return combatDamageStep(base.copy(turn = base.turn.copy(combat = combat))).pausedState
}
