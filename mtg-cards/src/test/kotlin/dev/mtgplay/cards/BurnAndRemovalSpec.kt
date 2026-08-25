package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.CostReduction
import dev.mtgplay.core.definition.EnchantRestriction
import dev.mtgplay.core.definition.Magnitude
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.TargetCondition
import dev.mtgplay.core.definition.TargetCount
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.Color
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * The `W8-C` removal family (BurnAndRemoval.kt) against the oracle cards: printed characteristics
 * (CR 201–208) and the declaration each printed clause maps onto. Every card's *behaviour* is played
 * through the engine elsewhere — the two new trigger conditions in `mtg-rules`' `TapAndDamageTriggerSpec`
 * and the target-conditional cost in its `TargetConditionalCostSpec` — so this suite pins the data, one
 * assertion per printed line.
 */
class BurnAndRemovalSpec :
    StringSpec({

        "CR 202: Dust to Dust is a {1}{W}{W} sorcery" {
            with(dustToDust.characteristics) {
                name shouldBe "Dust to Dust"
                manaCost?.render() shouldBe "{1}{W}{W}"
                cardTypes shouldBe persistentSetOf(CardType.SORCERY)
                supertypes shouldBe persistentSetOf<Supertype>()
                subtypes shouldBe persistentSetOf<Subtype>()
                powerToughness.shouldBeNull()
                colors shouldBe setOf(Color.WHITE)
            }
            dustToDust.timing shouldBe TimingClass.SORCERY_SPEED
        }

        "CR 115.1b: 'exile two target artifacts' is the artifact noun with an exact count of two" {
            dustToDust.targetSpec shouldBe
                TargetSpec.TargetPermanent(
                    restriction = PermanentRestriction.ARTIFACT,
                    count = TargetCount.Exactly(2),
                )
            // CR 601.2c: the minimum is what makes it uncastable against a single artifact — a demand,
            // not an "up to". Both bounds are two.
            dustToDust.targetSpec.count.minimum shouldBe 2
            dustToDust.targetSpec.count.maximum shouldBe 2
        }

        "CR 202: Cryoshatter is a {U} Enchantment — Aura" {
            with(cryoshatter.characteristics) {
                name shouldBe "Cryoshatter"
                manaCost?.render() shouldBe "{U}"
                cardTypes shouldBe persistentSetOf(CardType.ENCHANTMENT)
                subtypes shouldBe persistentSetOf(Subtype("Aura"))
                powerToughness.shouldBeNull()
                colors shouldBe setOf(Color.BLUE)
            }
            // CR 601.3a: an Aura is an enchantment spell, cast at sorcery speed.
            cryoshatter.timing shouldBe TimingClass.SORCERY_SPEED
        }

        "CR 303.4a: 'Enchant creature' is the unrestricted creature enchant restriction" {
            cryoshatter.targetSpec shouldBe TargetSpec.Enchantable(EnchantRestriction.CREATURE)
        }

        "CR 613.3 sublayer 7c: 'enchanted creature gets -5/-0' is a power modifier alone" {
            val effect = cryoshatter.staticContinuousEffects.single()
            effect.powerMod shouldBe Magnitude.Fixed(-5)
            effect.toughnessMod shouldBe Magnitude.Zero
        }

        "CR 603.2: 'becomes tapped or is dealt damage' is one ability with a disjunctive condition" {
            val ability = cryoshatter.triggeredAbilities.single()
            ability.condition shouldBe
                TriggerCondition.AnyOf(
                    persistentListOf(
                        TriggerCondition.EnchantedPermanentBecomesTapped,
                        TriggerCondition.EnchantedPermanentIsDealtDamage,
                    ),
                )
        }

        "CR 202: Ride's End is a {4}{W} instant" {
            with(ridesEnd.characteristics) {
                name shouldBe "Ride's End"
                manaCost?.render() shouldBe "{4}{W}"
                cardTypes shouldBe persistentSetOf(CardType.INSTANT)
                supertypes shouldBe persistentSetOf<Supertype>()
                subtypes shouldBe persistentSetOf<Subtype>()
                powerToughness.shouldBeNull()
                colors shouldBe setOf(Color.WHITE)
            }
            ridesEnd.timing shouldBe TimingClass.INSTANT_SPEED
        }

        "CR 115.1b: 'exile target creature or Vehicle' is the creature-or-Vehicle noun, one target" {
            ridesEnd.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.CREATURE_OR_VEHICLE)
            ridesEnd.targetSpec.count shouldBe TargetCount.ONE
        }

        "CR 601.2f: 'costs {3} less if it targets a tapped permanent' is a target-conditional reduction" {
            ridesEnd.costReduction shouldBe
                CostReduction.IfTargets(amount = 3, condition = TargetCondition.TAPPED_PERMANENT)
        }

        "the three cards carry no cost or casting machinery their oracle text does not print" {
            listOf(dustToDust, cryoshatter, ridesEnd).forEach { card ->
                card.castingPermissions shouldContainExactly emptyList()
                card.additionalCost.shouldBeNull()
                card.kicker.shouldBeNull()
                card.counterUnlessPaid.shouldBeNull()
                card.rebound shouldBe false
                card.modes shouldContainExactly emptyList()
            }
            // Only Ride's End prices itself off anything.
            dustToDust.costReduction.shouldBeNull()
            cryoshatter.costReduction.shouldBeNull()
        }

        "the registry holds all three under their printed names (CR 201)" {
            listOf("Dust to Dust", "Cryoshatter", "Ride's End").forEach { name ->
                MvpCards.definitions
                    .getValue(CardRef(name))
                    .characteristics.name shouldBe name
            }
        }
    })
