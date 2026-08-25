package dev.mtgplay.rules

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.engine.legalPriorityOptions
import dev.mtgplay.rules.engine.ninjutsuOptions
import dev.mtgplay.rules.engine.player
import dev.mtgplay.rules.engine.unblockedAttackersOf
import dev.mtgplay.rules.engine.updatePlayer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.toPersistentList

/**
 * `FW-NINJUTSU` (CR 702.49), `FW-TRIGCOMBAT` (CR 510.2) and `FW-OPTDRAW` (CR 601.3b).
 *
 * The suite is organised around the four claims the framework makes that a "special action" reading would
 * have got wrong, plus the enumeration window CR 509.1h defines.
 */
class NinjutsuSpec :
    StringSpec({

        // --- CR 509.1h: the window ------------------------------------------------------------------

        "CR 509.1h: no attacker is unblocked before blockers are declared, so ninjutsu is not enumerated" {
            val state =
                ninjutsuState(
                    aliceField = listOf(NinjaBoard("Rat")),
                    aliceHand = listOf("Deep Ninja"),
                    step = TurnStep.DECLARE_ATTACKERS,
                    attackers = listOf("Rat"),
                    // `null` is the pre-declare-blockers window: neither blocked nor unblocked yet.
                    blocks = null,
                )
            unblockedAttackersOf(state, alice).shouldBeEmpty()
            ninjutsuOptions(state, alice).shouldBeEmpty()
        }

        "CR 509.1h: an attacker nobody blocked is unblocked once blockers are declared, and ninjutsu appears" {
            val state =
                ninjutsuState(
                    aliceField = listOf(NinjaBoard("Rat")),
                    aliceHand = listOf("Deep Ninja"),
                    attackers = listOf("Rat"),
                    blocks = emptyList(),
                )
            unblockedAttackersOf(state, alice).shouldHaveSize(1)
            val options = ninjutsuOptions(state, alice)
            options.shouldHaveSize(1)
            options.single().card shouldBe CardRef("Deep Ninja")
            options.single().returnedAttacker shouldBe state.ninjaBattlefield("Rat").id
        }

        "CR 509.1h: a blocked attacker is not an unblocked one, so ninjutsu cannot return it" {
            val state =
                ninjutsuState(
                    aliceField = listOf(NinjaBoard("Rat")),
                    bobField = listOf(NinjaBoard("Blocker")),
                    aliceHand = listOf("Deep Ninja"),
                    attackers = listOf("Rat"),
                    blocks = listOf("Blocker" to "Rat"),
                )
            unblockedAttackersOf(state, alice).shouldBeEmpty()
            ninjutsuOptions(state, alice).shouldBeEmpty()
        }

        "CR 702.49a: ninjutsu stays enumerated in the end-of-combat step while an unblocked attacker remains" {
            val state =
                ninjutsuState(
                    aliceField = listOf(NinjaBoard("Rat")),
                    aliceHand = listOf("Deep Ninja"),
                    step = TurnStep.END_OF_COMBAT,
                    attackers = listOf("Rat"),
                    blocks = emptyList(),
                )
            ninjutsuOptions(state, alice).shouldHaveSize(1)
        }

        "ADR-005: every (ninja, unblocked attacker) pair is its own enumerated option" {
            val state =
                ninjutsuState(
                    aliceField = listOf(NinjaBoard("Rat"), NinjaBoard("Blocker")),
                    aliceHand = listOf("Deep Ninja", "Plain Ninja"),
                    attackers = listOf("Rat", "Blocker"),
                    blocks = emptyList(),
                )
            // Two ninjas times two unblocked attackers.
            ninjutsuOptions(state, alice).shouldHaveSize(4)
        }

        "CR 602.2g: a ninjutsu cost with no payment plan is not enumerated" {
            val state =
                ninjutsuState(
                    aliceField = listOf(NinjaBoard("Rat")),
                    aliceHand = listOf("Deep Ninja"),
                    // {1}{U} needs two sources; one cannot pay it.
                    aliceLands = 1,
                    attackers = listOf("Rat"),
                    blocks = emptyList(),
                )
            ninjutsuOptions(state, alice).shouldBeEmpty()
        }

        "CR 702.49a: the ninjutsu option rides the priority window's enumerated action space (ADR-005)" {
            val state =
                ninjutsuState(
                    aliceField = listOf(NinjaBoard("Rat")),
                    aliceHand = listOf("Deep Ninja"),
                    attackers = listOf("Rat"),
                    blocks = emptyList(),
                )
            legalPriorityOptions(state, alice)
                .filterIsInstance<PriorityOption.ActivateNinjutsu>()
                .shouldHaveSize(1)
        }

        "CR 702.49a: a non-creature card declaring ninjutsu is a definition defect and fails loudly" {
            val state =
                ninjutsuState(
                    aliceField = listOf(NinjaBoard("Rat")),
                    aliceHand = listOf("Fixture Ninja Tool"),
                    attackers = listOf("Rat"),
                    blocks = emptyList(),
                )
            shouldThrow<IllegalArgumentException> { ninjutsuOptions(state, alice) }
        }

        // --- CR 602.2b: activation pays the cost and uses the stack ---------------------------------

        "CR 602.2b: activating ninjutsu pays the cost — the attacker is in hand and the ability is on the stack" {
            val engine = DefaultGameEngine()
            val activated = activateNinjutsu(engine)
            val state = activated.pausedState
            // CR 702.49a: the returned attacker left the battlefield for its owner's hand as the cost was paid.
            state.sharedZones.battlefield.none { it.card == CardRef("Rat") } shouldBe true
            state.player(alice).hand.count { it.card == CardRef("Rat") } shouldBe 1
            // CR 506.4: and it left combat with it.
            state.turn.combat
                .shouldNotBeNull()
                .attackers
                .shouldBeEmpty()
            // CR 602.2 / CR 113.3b: the ability is on the stack, not resolved — this is the whole correction.
            state.sharedZones.stack.shouldHaveSize(1)
            state.sharedZones.stack
                .single()
                .shouldBeInstanceOf<StackEntry.ActivatedAbilityOnStack>()
            // CR 702.49a: the card is still in hand; nothing entered the battlefield yet.
            state.player(alice).hand.count { it.card == CardRef("Deep Ninja") } shouldBe 1
            state.events.filterIsInstance<GameEvent.NinjutsuActivated>().shouldHaveSize(1)
        }

        "CR 702.49a: the ninja arrives tapped and attacking only when the ability resolves" {
            val engine = DefaultGameEngine()
            val resolved = resolveNinjutsu(engine)
            val state = resolved.pausedState
            val ninja = state.ninjaBattlefield("Deep Ninja")
            // CR 702.49a: "tapped and attacking".
            ninja.tapped shouldBe true
            val combat = state.turn.combat.shouldNotBeNull()
            combat.attackers.map { it.attacker } shouldContain ninja.id
            // CR 702.49d: attacking the player the returned creature was attacking.
            combat.attackers.single { it.attacker == ninja.id }.defendingPlayer shouldBe bob
            // CR 509.1h: it arrives unblocked — nothing blocked it, and blockers are long since declared.
            (ninja.id in combat.blockedAttackers) shouldBe false
            state.events.filterIsInstance<GameEvent.NinjaEnteredAttacking>().shouldHaveSize(1)
        }

        "CR 508.1: a ninja put onto the battlefield attacking was never *declared* as an attacker" {
            val engine = DefaultGameEngine()
            val state = resolveNinjutsu(engine).pausedState
            val ninja = state.ninjaBattlefield("Deep Ninja")
            // It is attacking...
            state.turn.combat
                .shouldNotBeNull()
                .attackers
                .map { it.attacker } shouldContain ninja.id
            // ...and no declare-attackers action ever named it, which is what the CR 702.49a ruling turns on:
            // an ability that triggers "whenever this creature attacks" would not fire for it.
            state.events
                .filterIsInstance<GameEvent.AttackersDeclared>()
                .flatMap { it.attackers }
                .map { it.attacker }
                .contains(ninja.id) shouldBe false
        }

        "CR 603.6a: a ninja entering by ninjutsu fires its enters-the-battlefield triggers like any permanent" {
            val engine = DefaultGameEngine()
            val state = resolveNinjutsu(engine, ninja = "Trigger Ninja").pausedState
            // The entry routed through announceBattlefieldEntry, so the CR 603.6a trigger was not lost (T18).
            val fired =
                state.pendingTriggers.any { it.sourceCard == CardRef("Trigger Ninja") } ||
                    state.sharedZones.stack.any {
                        (it as? StackEntry.Ability)?.trigger?.sourceCard == CardRef("Trigger Ninja")
                    }
            fired shouldBe true
        }

        "CR 702.49a: a ninja that left its owner's hand before the ability resolved never enters the battlefield" {
            val engine = DefaultGameEngine()
            val activated = activateNinjutsu(engine)
            // The window only exists because ninjutsu uses the stack: between activation and resolution the
            // card sits in hand, and anything that moves it leaves the ability with nothing to put anywhere.
            val stolen =
                activated.pausedState.updatePlayer(alice) { seat ->
                    seat.copy(hand = seat.hand.filterNot { it.card == CardRef("Deep Ninja") }.toPersistentList())
                }
            val resumed = AdvanceResult.NeedsDecision(stolen, pausedRequestOf<DecisionRequest.ChooseAction>(stolen))
            val resolved = engine.passPriorityRound(resumed)
            resolved.pausedState.sharedZones.battlefield
                .none { it.card == CardRef("Deep Ninja") } shouldBe true
            // CR 701.5a: the cost stays paid regardless — the Rat is still in hand, not back on the battlefield.
            resolved.pausedState.sharedZones.battlefield
                .none { it.card == CardRef("Rat") } shouldBe true
        }
    })

// --- scenario drivers ---------------------------------------------------------------------------

/** Alice attacks with a Rat, nobody blocks, and she activates [ninja]'s ninjutsu returning the Rat. */
private fun activateNinjutsu(
    engine: DefaultGameEngine,
    ninja: String = "Deep Ninja",
): AdvanceResult {
    val state =
        ninjutsuState(
            aliceField = listOf(NinjaBoard("Rat")),
            aliceHand = listOf(ninja),
            attackers = listOf("Rat"),
            blocks = emptyList(),
        )
    val request = pausedRequestOf<DecisionRequest.ChooseAction>(state)
    val index = request.options.indexOfFirst { it is PriorityOption.ActivateNinjutsu }
    require(index >= 0) { "no ninjutsu option in ${request.options}" }
    val gathering = engine.advance(state, Decision.SingleSelect(request.id, index))
    // CR 602.2g: the payment plan for {1}{U}.
    val payment = gathering.pending<DecisionRequest.ChoosePaymentPlan>()
    return engine.advance(gathering.pausedState, Decision.SingleSelect(payment.id, 0))
}

/** [activateNinjutsu], then both players pass so the ability resolves (CR 117.4, CR 608.2). */
private fun resolveNinjutsu(
    engine: DefaultGameEngine,
    ninja: String = "Deep Ninja",
): AdvanceResult = engine.passPriorityRound(activateNinjutsu(engine, ninja))
