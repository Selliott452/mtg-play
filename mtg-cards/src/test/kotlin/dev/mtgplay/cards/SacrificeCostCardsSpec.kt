package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AdditionalCost
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.SacrificeFilter
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaCost
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * The four `FW-ADDSAC` gauntlet cards, each checked against its printed Oracle text: the cost shape,
 * the filter, and the clause the resolution reads. Card-definition fidelity only — the framework's
 * behaviour (enumeration, reservation, payment) is asserted in `mtg-rules`.
 */
class SacrificeCostCardsSpec :
    StringSpec({
        val artifactOrCreature = SacrificeFilter(persistentSetOf(CardType.ARTIFACT, CardType.CREATURE))

        "CR 601.2b: Eviscerator's Insight is a {1}{B} instant that sacrifices an artifact or creature" {
            evisceratorsInsight.characteristics.manaCost shouldBe ManaCost.parse("{1}{B}")
            evisceratorsInsight.timing shouldBe TimingClass.INSTANT_SPEED
            evisceratorsInsight.targetSpec shouldBe TargetSpec.None
            evisceratorsInsight.additionalCost shouldBe AdditionalCost.Sacrifice(1, artifactOrCreature)
        }

        "CR 702.34a: Eviscerator's Insight's flashback is {4}{B}, and the additional cost rides along" {
            val flashback =
                evisceratorsInsight.castingPermissions
                    .single()
                    .shouldBeInstanceOf<CastingPermission.Flashback>()
            flashback.cost shouldBe ManaCost.parse("{4}{B}")
            // "and any additional costs": the sacrifice is declared on the card, not on the permission,
            // so a flashback cast pays it too — and the permission itself carries no sacrifice of its own.
            flashback.sacrifice shouldBe null
            evisceratorsInsight.additionalCost shouldBe AdditionalCost.Sacrifice(1, artifactOrCreature)
        }

        "CR 601.2b: Reckoner's Bargain is a {1}{B} instant that sacrifices an artifact or creature" {
            reckonersBargain.characteristics.manaCost shouldBe ManaCost.parse("{1}{B}")
            reckonersBargain.timing shouldBe TimingClass.INSTANT_SPEED
            reckonersBargain.additionalCost shouldBe AdditionalCost.Sacrifice(1, artifactOrCreature)
            // No flashback: the Bargain's only cast is the normal one.
            reckonersBargain.castingPermissions shouldBe emptyList()
        }

        "CR 602.1: Krark-Clan Shaman's whole ability cost is 'Sacrifice an artifact' — no mana, no tap" {
            krarkClanShaman.characteristics.manaCost shouldBe ManaCost.parse("{R}")
            krarkClanShaman.characteristics.cardTypes shouldBe persistentSetOf(CardType.CREATURE)
            val ability = krarkClanShaman.activatedAbilities.single()
            ability.cost shouldContainExactly
                listOf(AbilityCost.Sacrifice(SacrificeFilter(persistentSetOf(CardType.ARTIFACT))))
            // CR 115.1: "each creature without flying" is not a target.
            ability.targetSpec shouldBe TargetSpec.None
        }

        "CR 701.17: the Shaman is a creature, so its own 'Sacrifice an artifact' filter never matches it" {
            val filter =
                krarkClanShaman.activatedAbilities
                    .single()
                    .cost
                    .filterIsInstance<AbilityCost.Sacrifice>()
                    .single()
                    .filter
            // The printed text says "an artifact", not "another artifact"; the types do the excluding.
            filter.anyOfCardTypes.any { it in krarkClanShaman.characteristics.cardTypes } shouldBe false
        }

        "CR 602.1: Makeshift Munitions costs {1} then a sacrifice, in printed order, and targets any target" {
            makeshiftMunitions.characteristics.manaCost shouldBe ManaCost.parse("{1}{R}")
            makeshiftMunitions.characteristics.cardTypes shouldBe persistentSetOf(CardType.ENCHANTMENT)
            val ability = makeshiftMunitions.activatedAbilities.single()
            ability.cost shouldContainExactly
                listOf(AbilityCost.Mana(ManaCost.parse("{1}")), AbilityCost.Sacrifice(artifactOrCreature))
            ability.targetSpec shouldBe TargetSpec.AnyTarget
        }

        "CR 701.17: the Munitions is an enchantment, so it never appears among its own cost's options" {
            val filter =
                makeshiftMunitions.activatedAbilities
                    .single()
                    .cost
                    .filterIsInstance<AbilityCost.Sacrifice>()
                    .single()
                    .filter
            filter.anyOfCardTypes.any { it in makeshiftMunitions.characteristics.cardTypes } shouldBe false
        }

        "ADR-009: all four are registered under their printed names" {
            listOf(evisceratorsInsight, reckonersBargain, krarkClanShaman, makeshiftMunitions).forEach { definition ->
                MvpCards.definitions[CardRef(definition.characteristics.name)] shouldBe definition
            }
        }
    })
