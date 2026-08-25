package dev.mtgplay.rules

import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.definition.EnchantRestriction
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.engine.ActiveEffect
import dev.mtgplay.rules.engine.Chooser
import dev.mtgplay.rules.engine.CreatureDeathCause
import dev.mtgplay.rules.engine.Layer
import dev.mtgplay.rules.engine.LayeredCharacteristics
import dev.mtgplay.rules.engine.SbaOutcome
import dev.mtgplay.rules.engine.StateBasedAction
import dev.mtgplay.rules.engine.applicableStateBasedActions
import dev.mtgplay.rules.engine.applyLayer
import dev.mtgplay.rules.engine.effectiveKeywords
import dev.mtgplay.rules.engine.effectivePower
import dev.mtgplay.rules.engine.effectiveToughness
import dev.mtgplay.rules.engine.isTargetLegal
import dev.mtgplay.rules.engine.layeredCharacteristics
import dev.mtgplay.rules.engine.layeredToughness
import dev.mtgplay.rules.engine.legalTargets
import dev.mtgplay.rules.engine.performStateBasedActions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList

/**
 * The P4.1 continuous-effect layer engine (CR 613), exercised entirely by fixture Auras
 * (docs/design/layer-system.md). Covers layered characteristic computation (layers 6 and 7c),
 * dynamic-magnitude reevaluation (CR 613.3c), the loud gates (§1), enchant-restriction legality
 * (CR 303.4a), and the CR 704.5m fall-off state-based action and its composition with creature death.
 */
class LayerSystemSpec :
    StringSpec({

        "CR 613 layers 6 and 7c: a +2/+2 first-strike Aura yields the correct layered P/T and keywords" {
            // Ent 2/2 enchanted by Fixture Cloak (+2/+2, grants first strike).
            val state = auraState(listOf(bfObject(0, "Ent"), bfObject(1, "Fixture Cloak", attachedTo = 0)))
            val layered = layeredCharacteristics(state, ObjectId(0))
            layered.power shouldBe 4
            layered.toughness shouldBe 4
            layered.keywords.shouldContainExactlyInAnyOrder(Keyword.FIRST_STRIKE)
        }

        "CR 613: the three effective* combat seams delegate to the layer engine" {
            val state = auraState(listOf(bfObject(0, "Ent"), bfObject(1, "Fixture Cloak", attachedTo = 0)))
            // The seams combat and the SBAs read now return the layered values, not the printed ones.
            effectivePower(state, ObjectId(0)) shouldBe 4
            effectiveToughness(state, ObjectId(0)) shouldBe 4
            effectiveKeywords(state, ObjectId(0)).shouldContainExactlyInAnyOrder(Keyword.FIRST_STRIKE)
        }

        "CR 613 layer isolation: a keyword grant never moves P/T; a P/T modifier never adds keywords" {
            // Fixture Mark grants first strike only; Fixture Ward is +0/+2 only.
            val marked = auraState(listOf(bfObject(0, "Ent"), bfObject(1, "Fixture Mark", attachedTo = 0)))
            layeredCharacteristics(marked, ObjectId(0)).let {
                it.power shouldBe 2
                it.toughness shouldBe 2
                it.keywords.shouldContainExactlyInAnyOrder(Keyword.FIRST_STRIKE)
            }
            val warded = auraState(listOf(bfObject(0, "Ent"), bfObject(1, "Fixture Ward", attachedTo = 0)))
            layeredCharacteristics(warded, ObjectId(0)).let {
                it.power shouldBe 2
                it.toughness shouldBe 4
                it.keywords.shouldBeEmpty()
            }
        }

        "CR 613.2: an object with no active Aura keeps its printed characteristics" {
            val state = auraState(listOf(bfObject(0, "Ent")))
            layeredCharacteristics(state, ObjectId(0)).let {
                it.power shouldBe 2
                it.toughness shouldBe 2
                it.keywords.shouldBeEmpty()
            }
        }

        "CR 613.3c: a dynamic +N/+N Aura recounts when a new enchantment enters, with no explicit recompute" {
            // Fixture Mask is +N/+N, N = enchantments on the battlefield.
            val oneEnchantment =
                auraState(listOf(bfObject(0, "Ent"), bfObject(1, "Fixture Mask", attachedTo = 0)))
            // N = 1 (Mask itself): Ent is 2+1 / 2+1.
            layeredCharacteristics(oneEnchantment, ObjectId(0)).power shouldBe 3
            layeredToughness(oneEnchantment, ObjectId(0)) shouldBe 3

            // A second enchantment enters (Fixture Growth on a Meadow, elsewhere) — no recompute call.
            val twoEnchantments =
                auraState(
                    listOf(
                        bfObject(0, "Ent"),
                        bfObject(1, "Fixture Mask", attachedTo = 0),
                        bfObject(2, "Meadow"),
                        bfObject(3, "Fixture Growth", attachedTo = 2),
                    ),
                )
            // N = 2 now: Ent jumps to 2+2 / 2+2 purely because the board changed (CR 613.3c).
            layeredCharacteristics(twoEnchantments, ObjectId(0)).power shouldBe 4
            layeredToughness(twoEnchantments, ObjectId(0)) shouldBe 4
        }

        "CR 704.5g: layered toughness — not printed — decides lethality (a buffed creature survives)" {
            // Ent 2/2 with 3 marked damage would be dead on printed toughness, but Fixture Cloak makes
            // it 4/4: 3 is sublethal to layered toughness, so no death state-based action applies.
            val buffed =
                auraState(
                    listOf(bfObject(0, "Ent", damageMarked = 3), bfObject(1, "Fixture Cloak", attachedTo = 0)),
                )
            applicableStateBasedActions(buffed).shouldBeEmpty()
        }

        "CR 704.5g and §8 headline: destroying a toughness Aura drops toughness and death fires the same check" {
            // Ent 2/2 kept alive by Fixture Ward (+0/+2 -> toughness 4) with 3 marked damage.
            val alive =
                auraState(
                    listOf(bfObject(0, "Ent", damageMarked = 3), bfObject(1, "Fixture Ward", attachedTo = 0)),
                )
            // No stale read: the buffed creature is alive, nothing to do.
            applicableStateBasedActions(alive).shouldBeEmpty()

            // The Aura is destroyed (leaves the battlefield). Layered toughness recomputes to 2 on the
            // next read, so 3 marked damage is now lethal — the death SBA fires on the very next check.
            val survivors =
                alive.sharedZones.battlefield
                    .filterNot { it.card.name == "Fixture Ward" }
                    .toPersistentList()
            val afterDestroy = alive.copy(sharedZones = alive.sharedZones.copy(battlefield = survivors))
            applicableStateBasedActions(afterDestroy) shouldContainExactly
                listOf(StateBasedAction.CreatureDies(ObjectId(0), CreatureDeathCause.LETHAL_DAMAGE))
            val resolved = performStateBasedActions(afterDestroy).shouldBeInstanceOf<SbaOutcome.Continued>()
            resolved.state.sharedZones.battlefield
                .shouldBeEmpty()
        }

        "CR 704.5m and CR 400.7: an Aura on a creature that dies falls off on the next check" {
            // Ent 2/2 + Fixture Cloak (+2/+2 -> 4/4) with 4 marked damage: lethal even buffed.
            val state =
                auraState(
                    listOf(bfObject(0, "Ent", damageMarked = 4), bfObject(1, "Fixture Cloak", attachedTo = 0)),
                )
            val outcome = performStateBasedActions(state).shouldBeInstanceOf<SbaOutcome.Continued>()
            // Batch 1 kills Ent (layered toughness 4, marked 4); batch 2 lets the now-dangling Cloak
            // fall off (CR 704.5m). The battlefield is empty and both sit in alice's graveyard.
            outcome.state.sharedZones.battlefield
                .shouldBeEmpty()
            outcome.state.players
                .getValue(alice)
                .graveyard
                .map { it.card.name }
                .shouldContainExactlyInAnyOrder("Ent", "Fixture Cloak")
        }

        "CR 704.5m: an Aura attached to nothing on the battlefield falls off" {
            // A Cloak whose attachedTo names an id that is not on the battlefield (its creature is gone).
            val state = auraState(listOf(bfObject(1, "Fixture Cloak", attachedTo = 99)))
            applicableStateBasedActions(state) shouldContainExactly
                listOf(StateBasedAction.AuraFallsOff(ObjectId(1)))
        }

        "CR 303.4a: enchant creature — only creatures are legal targets, both directions" {
            val state = auraState(listOf(bfObject(0, "Ent"), bfObject(1, "Meadow")))
            val spec = TargetSpec.Enchantable(EnchantRestriction.CREATURE)
            legalTargets(state, spec, alice, Chooser.Nobody) shouldContainExactly listOf(Target.Permanent(ObjectId(0)))
            isTargetLegal(state, spec, Target.Permanent(ObjectId(1)), alice, Chooser.Nobody) shouldBe false
        }

        "CR 303.4a: enchant land — only lands are legal targets, both directions" {
            val state = auraState(listOf(bfObject(0, "Ent"), bfObject(1, "Meadow")))
            val spec = TargetSpec.Enchantable(EnchantRestriction.LAND)
            legalTargets(state, spec, alice, Chooser.Nobody) shouldContainExactly listOf(Target.Permanent(ObjectId(1)))
            isTargetLegal(state, spec, Target.Permanent(ObjectId(0)), alice, Chooser.Nobody) shouldBe false
        }

        "CR 303.4a and CR 205.3: enchant Forest — only a Forest-subtype land is legal" {
            val state = auraState(listOf(bfObject(0, "Meadow"), bfObject(1, "Thicket")))
            val spec = TargetSpec.Enchantable(EnchantRestriction.FOREST)
            legalTargets(state, spec, alice, Chooser.Nobody) shouldContainExactly listOf(Target.Permanent(ObjectId(1)))
            isTargetLegal(state, spec, Target.Permanent(ObjectId(0)), alice, Chooser.Nobody) shouldBe false
        }

        "CR 303.4a: enchant creature you control — control is ownership (§4), both directions" {
            // Ent owned by alice, Toad owned by bob.
            val state = auraState(listOf(bfObject(0, "Ent", owner = alice), bfObject(1, "Toad", owner = bob)))
            val spec = TargetSpec.Enchantable(EnchantRestriction.CREATURE_YOU_CONTROL)
            legalTargets(state, spec, alice, Chooser.Nobody) shouldContainExactly listOf(Target.Permanent(ObjectId(0)))
            legalTargets(state, spec, bob, Chooser.Nobody) shouldContainExactly listOf(Target.Permanent(ObjectId(1)))
        }

        "CR 613 §1: an effect that classifies into no implemented layer fails loudly (unimplemented kind)" {
            // Fixture Hollow carries an empty static effect — no layer-6 grant, no layer-7c modifier.
            val state = auraState(listOf(bfObject(0, "Ent"), bfObject(1, "Fixture Hollow", attachedTo = 0)))
            val error = shouldThrow<IllegalArgumentException> { layeredCharacteristics(state, ObjectId(0)) }
            error.message.shouldBeInstanceOf<String>() shouldContain "613"
        }

        "CR 613 §1: applying an effect in an unpopulated layer fails loudly (the spine's loud gate)" {
            // Route a real effect to an unpopulated layer directly: the spine refuses it rather than
            // silently dropping it. Layer 4 (type-changing) is unimplemented in the MVP pool.
            val state = auraState(listOf(bfObject(0, "Ent")))
            val base =
                LayeredCharacteristics(
                    power = 2,
                    toughness = 2,
                    keywords = persistentSetOf(),
                    manaAbilities = persistentListOf(),
                )
            val effect = state.staticEffectOf("Fixture Cloak")
            val active =
                ActiveEffect(
                    source = ObjectId(1),
                    affected = ObjectId(0),
                    grantedKeywords = effect.grantedKeywords,
                    grantedManaAbilities = effect.grantedManaAbilities,
                    powerMod = effect.powerMod,
                    toughnessMod = effect.toughnessMod,
                    timestamp = 1,
                )
            val error =
                shouldThrow<IllegalArgumentException> {
                    applyLayer(state, base, Layer.TYPE, listOf(active), persistentMapOf())
                }
            error.message.shouldBeInstanceOf<String>() shouldContain "613"
        }
    })
