package dev.mtgplay.rules

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.enumeratePaymentPlans
import dev.mtgplay.rules.engine.layeredCharacteristics
import dev.mtgplay.rules.engine.productionProfile
import dev.mtgplay.rules.engine.resolveTopOfStack
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

/**
 * The P4.1 Aura casting and resolution path (docs/design/layer-system.md §4): an Aura targets via
 * [dev.mtgplay.core.definition.TargetSpec.Enchantable] (CR 601.2c), enters the battlefield attached
 * (CR 303.4f), or fizzles if its target is gone at resolution (CR 608.2b). Also the layer-6
 * mana-grant on a land flowing through payment enumeration, and a full engine-driven cast that
 * replays identically (ADR-006).
 */
class AuraCastingSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 303.4f and CR 608.3: an Aura resolves onto the battlefield attached to its target" {
            val state = auraStackState(listOf(bfObject(0, "Ent")), auraName = "Fixture Cloak", auraId = 1, targetId = 0)
            val entered = resolveTopOfStack(state).pausedState

            val ent = entered.bf("Ent")
            val aura = entered.sharedZones.battlefield.first { it.card.name == "Fixture Cloak" }
            aura.attachedTo shouldBe ent.id
            entered.sharedZones.stack.shouldBeEmpty()
            // The layer engine sees the freshly attached Aura at once: Ent is 4/4.
            layeredCharacteristics(entered, ent.id).power shouldBe 4
            entered.events.filterIsInstance<GameEvent.PermanentEntered>() shouldHaveSize 1
            entered.events.filterIsInstance<GameEvent.AuraAttached>().single().let {
                it.aura shouldBe aura.id
                it.attachedTo shouldBe ent.id
            }
        }

        "CR 608.2b: an Aura whose target is gone at resolution fizzles to the graveyard and never enters" {
            // Target id 0 is not on the battlefield — the creature died while the Aura was on the stack.
            val state = auraStackState(battlefield = emptyList(), auraName = "Fixture Cloak", auraId = 1, targetId = 0)
            val after = resolveTopOfStack(state).pausedState

            after.sharedZones.battlefield.shouldBeEmpty()
            after.sharedZones.stack.shouldBeEmpty()
            after.players
                .getValue(alice)
                .graveyard
                .map { it.card.name } shouldContainExactly listOf("Fixture Cloak")
            after.events.filterIsInstance<GameEvent.SpellFizzled>() shouldHaveSize 1
            after.events.filterIsInstance<GameEvent.AuraAttached>().shouldBeEmpty()
        }

        "CR 613 layer 6: a mana-granting Aura lets an enchanted land tap for the granted color in payment enumeration" {
            // Meadow ({T}: add {G}) enchanted by Fixture Growth (grants {T}: add one mana of any color).
            val enchanted = auraState(listOf(bfObject(0, "Meadow"), bfObject(1, "Fixture Growth", attachedTo = 0)))
            // One alternative per grantable colour, each adding a single mana (CR 605.1a).
            productionProfile(enchanted, enchanted.bf("Meadow")) shouldBe
                listOf(ManaType.WHITE, ManaType.BLUE, ManaType.BLACK, ManaType.RED, ManaType.GREEN)
                    .map { listOf(it) }
            // {W} is payable now — only because of the grant.
            enumeratePaymentPlans(enchanted, alice, ManaCost.parse("{W}")).shouldNotBeEmpty()

            // Without the Aura the Meadow makes only {G}, so {W} is unpayable.
            val plain = auraState(listOf(bfObject(0, "Meadow")))
            productionProfile(plain, plain.bf("Meadow")) shouldBe listOf(listOf(ManaType.GREEN))
            enumeratePaymentPlans(plain, alice, ManaCost.parse("{W}")).shouldBeEmpty()
        }

        "CR 601 and CR 303.4f: casting an Aura through the engine enters it attached, and the run replays identically" {
            val start = auraCastingState(listOf("Fixture Cloak"), listOf(bfObject(0, "Meadow"), bfObject(1, "Ent")))
            val decisions = mutableListOf<Decision>()

            val castReq = pausedRequestOf<DecisionRequest.ChooseAction>(start)
            val cast = castDecision(castReq, "Fixture Cloak")
            decisions += cast
            var result = engine.advance(start, cast)

            val targetReq = result.pending<DecisionRequest.ChooseTargets>()
            // Enumeration completeness: only the creature Ent is a legal enchant target, not the Meadow.
            targetReq.options shouldContainExactly listOf(Target.Permanent(ObjectId(1)))
            val target = Decision.SingleSelect(targetReq.id, 0)
            decisions += target
            result = engine.advance(result.pausedState, target)

            val pay = planDecision(result.pending<DecisionRequest.ChoosePaymentPlan>())
            decisions += pay
            result = engine.advance(result.pausedState, pay)

            // The Aura is on the stack; alice then bob pass, and it resolves onto the battlefield.
            val pass1 = passDecision(result.pending<DecisionRequest.ChooseAction>())
            decisions += pass1
            result = engine.advance(result.pausedState, pass1)
            val pass2 = passDecision(result.pending<DecisionRequest.ChooseAction>())
            decisions += pass2
            result = engine.advance(result.pausedState, pass2)

            val ent = result.pausedState.bf("Ent")
            val aura =
                result.pausedState.sharedZones.battlefield
                    .first { it.card.name == "Fixture Cloak" }
            aura.attachedTo shouldBe ent.id
            layeredCharacteristics(result.pausedState, ent.id).power shouldBe 4

            // Replay: the same decision list from the same start reproduces the same state (ADR-006).
            var replay = engine.advance(start, decisions.first())
            for (decision in decisions.drop(1)) replay = engine.advance(replay.pausedState, decision)
            replay.pausedState shouldBe result.pausedState
        }
    })
