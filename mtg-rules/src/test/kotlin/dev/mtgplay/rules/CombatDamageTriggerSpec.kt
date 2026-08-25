package dev.mtgplay.rules

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.player
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * `FW-TRIGCOMBAT` (CR 510.2, CR 603.2) and `FW-OPTDRAW` (CR 601.3b) — the combat-damage-to-a-player
 * trigger and the bare optional draw that Ninja of the Deep Hours hangs off it.
 *
 * The properties worth pinning are the two narrowings that separate this condition from the
 * enchanted-creature-deals-damage one the engine already had: it must be **combat** damage, and it must
 * reach a **player**.
 */
class CombatDamageTriggerSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 510.2: an unblocked attacker that deals combat damage to a player fires its trigger" {
            val afterDamage = engine.passPriorityRound(unblockedNinjaAttack()).pausedState
            // The trigger is on the stack (or queued to be put there) for its controller.
            triggerPresent(afterDamage) shouldBe true
        }

        "CR 510.1c: an attacker whose damage all went to a blocker deals none to a player and fires nothing" {
            val state =
                ninjutsuState(
                    aliceField = listOf(NinjaBoard("Deep Ninja")),
                    // Blocker is 2/2 and Deep Ninja is 2/2: all of the ninja's damage goes to the blocker.
                    bobField = listOf(NinjaBoard("Blocker")),
                    aliceHand = emptyList(),
                    attackers = listOf("Deep Ninja"),
                    blocks = listOf("Blocker" to "Deep Ninja"),
                )
            val afterDamage =
                engine.passPriorityRound(AdvanceResult.NeedsDecision(state, pausedRequestOf(state))).pausedState
            afterDamage.players.getValue(bob).life shouldBe STARTING_LIFE
            triggerPresent(afterDamage) shouldBe false
        }

        "CR 601.3b: the optional draw is a yes/no, and accepting draws the card" {
            val yesNo = toOptionalDrawYesNo(engine)
            val before =
                yesNo.pausedState
                    .player(alice)
                    .hand.size
            val request = yesNo.pending<DecisionRequest.ChooseYesNo>()
            val accepted =
                engine.advance(
                    yesNo.pausedState,
                    Decision.SingleSelect(request.id, DecisionRequest.ChooseYesNo.ACCEPT),
                )
            accepted.pausedState
                .player(alice)
                .hand.size shouldBe before + 1
            // CR 113.7a: the ability ceased to exist once its clause finished.
            accepted.pausedState.sharedZones.stack
                .shouldBeEmpty()
        }

        "CR 601.3b: declining the optional draw draws nothing — the 'may' is a real choice (ADR-005)" {
            val yesNo = toOptionalDrawYesNo(engine)
            val before =
                yesNo.pausedState
                    .player(alice)
                    .hand.size
            val request = yesNo.pending<DecisionRequest.ChooseYesNo>()
            val declined =
                engine.advance(
                    yesNo.pausedState,
                    Decision.SingleSelect(request.id, DecisionRequest.ChooseYesNo.DECLINE),
                )
            declined.pausedState
                .player(alice)
                .hand.size shouldBe before
            declined.pausedState.sharedZones.stack
                .shouldBeEmpty()
            declined.pausedState.events
                .filterIsInstance<GameEvent.CardDrawn>()
                .shouldHaveSize(0)
        }
    })

/** Alice attacks with an unblocked Deep Ninja; the returned result is paused at the pre-damage window. */
private fun unblockedNinjaAttack(): AdvanceResult {
    val state =
        ninjutsuState(
            aliceField = listOf(NinjaBoard("Deep Ninja")),
            aliceHand = emptyList(),
            attackers = listOf("Deep Ninja"),
            blocks = emptyList(),
        )
    return AdvanceResult.NeedsDecision(state, pausedRequestOf(state))
}

/** Whether the Deep Ninja's combat-damage trigger is on the stack or queued for it (CR 603.3b). */
private fun triggerPresent(state: dev.mtgplay.core.state.GameState): Boolean =
    state.pendingTriggers.any { it.sourceCard == CardRef("Deep Ninja") } ||
        state.sharedZones.stack.any {
            (it as? StackEntry.Ability)?.trigger?.sourceCard == CardRef("Deep Ninja")
        }

/** Drives an unblocked ninja attack through combat damage and the trigger, to the optional-draw yes/no. */
private fun toOptionalDrawYesNo(engine: DefaultGameEngine): AdvanceResult {
    var current = engine.passPriorityRound(unblockedNinjaAttack())
    var guard = 0
    while (current !is AdvanceResult.NeedsDecision || current.request !is DecisionRequest.ChooseYesNo) {
        check(guard++ < 6) { "the optional-draw yes/no never surfaced; reached ${current.pending<DecisionRequest>()}" }
        current = engine.passPriorityRound(current)
    }
    return current
}
