package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.EnchantRestriction
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaSymbol
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * The gauntlet Tier-0 packet's twelve cards against the oracle card: printed characteristics
 * (CR 201–208) and the declaration each printed clause maps onto — Phyrexian mana (CR 107.4), the
 * cast-trigger filter (CR 603.2e), enters-the-battlefield and dies triggers (CR 603.6a–b), lifelink
 * and trample (CR 702.15, CR 702.19), flashback and plot (CR 702.34, CR 702.140), and a `{T}`
 * activated ability (CR 602). Every card's *behaviour* is played end-to-end in
 * `GauntletTierZeroAcceptanceSpec`; this suite pins the data.
 */
class GauntletTierZeroSpec :
    StringSpec({

        "CR 202: the printed type lines and costs match the oracle cards" {
            data class Expected(
                val definition: SpellDefinition,
                val name: String,
                val cost: String,
                val type: CardType,
                val timing: TimingClass,
            )

            listOf(
                Expected(gutShot, "Gut Shot", "{R/P}", CardType.INSTANT, TimingClass.INSTANT_SPEED),
                Expected(galvanicBlast, "Galvanic Blast", "{R}", CardType.INSTANT, TimingClass.INSTANT_SPEED),
                Expected(breathWeapon, "Breath Weapon", "{2}{R}", CardType.INSTANT, TimingClass.INSTANT_SPEED),
                Expected(
                    endTheFestivities,
                    "End the Festivities",
                    "{R}",
                    CardType.SORCERY,
                    TimingClass.SORCERY_SPEED,
                ),
                Expected(gnawToTheBone, "Gnaw to the Bone", "{2}{G}", CardType.INSTANT, TimingClass.INSTANT_SPEED),
                Expected(
                    unionOfTheThirdPath,
                    "Union of the Third Path",
                    "{2}{W}",
                    CardType.INSTANT,
                    TimingClass.INSTANT_SPEED,
                ),
            ).forEach { expected ->
                with(expected.definition.characteristics) {
                    name shouldBe expected.name
                    manaCost?.render() shouldBe expected.cost
                    cardTypes shouldBe persistentSetOf(expected.type)
                    supertypes shouldBe persistentSetOf<Supertype>()
                    subtypes shouldBe persistentSetOf<Subtype>()
                    powerToughness.shouldBeNull()
                }
                expected.definition.timing shouldBe expected.timing
            }
        }

        "CR 302: the four creatures are sorcery-speed, untargeted permanent spells with their printed boxes" {
            data class Expected(
                val definition: SpellDefinition,
                val name: String,
                val cost: String,
                val power: Int,
                val toughness: Int,
                val subtypes: Set<Subtype>,
                val keywords: Set<Keyword>,
            )

            listOf(
                Expected(
                    healerOfTheGlade,
                    "Healer of the Glade",
                    "{G}",
                    1,
                    2,
                    setOf(Subtype("Elemental")),
                    emptySet(),
                ),
                Expected(
                    outlawMedic,
                    "Outlaw Medic",
                    "{1}{W}",
                    1,
                    3,
                    setOf(Subtype("Human"), Subtype("Rogue")),
                    setOf(Keyword.LIFELINK),
                ),
                Expected(
                    spinewoodsPaladin,
                    "Spinewoods Paladin",
                    "{4}{G}",
                    5,
                    4,
                    setOf(Subtype("Human"), Subtype("Knight")),
                    setOf(Keyword.TRAMPLE),
                ),
                Expected(wellwisher, "Wellwisher", "{1}{G}", 1, 1, setOf(Subtype("Elf")), emptySet()),
                Expected(
                    murmuringMystic,
                    "Murmuring Mystic",
                    "{3}{U}",
                    1,
                    5,
                    setOf(Subtype("Human"), Subtype("Wizard")),
                    emptySet(),
                ),
            ).forEach { expected ->
                with(expected.definition.characteristics) {
                    name shouldBe expected.name
                    manaCost?.render() shouldBe expected.cost
                    cardTypes shouldBe persistentSetOf(CardType.CREATURE)
                    subtypes shouldBe expected.subtypes.toSet()
                    powerToughness shouldBe PrintedPowerToughness(expected.power, expected.toughness)
                    keywords shouldBe expected.keywords.toSet()
                }
                expected.definition.timing shouldBe TimingClass.SORCERY_SPEED
                expected.definition.targetSpec shouldBe TargetSpec.None
            }
        }

        "CR 107.4: Gut Shot's whole cost is one Phyrexian symbol — mana value 1, and red (CR 202.2)" {
            gutShot.characteristics.manaCost shouldBe ManaCost.parse("{R/P}")
            gutShot.characteristics.manaCost
                ?.symbols shouldContainExactly persistentListOf(ManaSymbol.Phyrexian(Color.RED))
            gutShot.characteristics.manaValue shouldBe 1
            gutShot.characteristics.colors shouldBe setOf(Color.RED)
        }

        "CR 115.4: only the two single-target burn spells target; the sweepers and lifegain target nothing" {
            listOf(gutShot, galvanicBlast).forEach { it.targetSpec shouldBe TargetSpec.AnyTarget }
            listOf(
                breathWeapon,
                endTheFestivities,
                gnawToTheBone,
                unionOfTheThirdPath,
            ).forEach { it.targetSpec shouldBe TargetSpec.None }
        }

        "CR 303.4a: Spirit Link is a {W} enchant-creature Aura with no static ability at all" {
            with(spiritLink.characteristics) {
                name shouldBe "Spirit Link"
                manaCost?.render() shouldBe "{W}"
                cardTypes shouldBe persistentSetOf(CardType.ENCHANTMENT)
                subtypes shouldBe persistentSetOf(Subtype("Aura"))
            }
            spiritLink.targetSpec shouldBe TargetSpec.Enchantable(EnchantRestriction.CREATURE)
            // Its whole text below the enchant line is a trigger, so the CR 613 layer engine sees nothing.
            spiritLink.staticContinuousEffects.shouldBeEmpty()
            spiritLink.triggeredAbilities
                .single()
                .condition shouldBe TriggerCondition.EnchantedCreatureDealsDamage
        }

        "CR 603.6a: the three enters-the-battlefield lifegain triggers are battlefield-scoped and self-referential" {
            listOf(healerOfTheGlade, spinewoodsPaladin).forEach { definition ->
                val trigger = definition.triggeredAbilities.single()
                trigger.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
                trigger.zoneScope shouldBe TriggerZoneScope.Battlefield
            }
        }

        "CR 603.6b: Outlaw Medic's dies trigger is the put-into-graveyard-from-battlefield condition" {
            val trigger = outlawMedic.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.PutIntoGraveyardFromBattlefieldSelf
            trigger.zoneScope shouldBe TriggerZoneScope.Battlefield
        }

        "CR 603.2e: Murmuring Mystic watches the same instant-or-sorcery casts Guttersnipe does" {
            val condition =
                murmuringMystic.triggeredAbilities
                    .single()
                    .condition
                    .shouldBeInstanceOf<TriggerCondition.SpellCast>()
            condition shouldBe
                TriggerCondition.SpellCast(
                    spellTypes = persistentSetOf(CardType.INSTANT, CardType.SORCERY),
                    controlledByYou = true,
                )
            condition shouldBe
                guttersnipe.triggeredAbilities
                    .single()
                    .condition
        }

        "CR 111.4: the Bird Illusion token is a 1/1 Bird Illusion with flying and no mana cost" {
            with(birdIllusionToken.characteristics) {
                name shouldBe "Bird Illusion"
                manaCost.shouldBeNull()
                cardTypes shouldBe persistentSetOf(CardType.CREATURE)
                subtypes shouldBe persistentSetOf(Subtype("Bird"), Subtype("Illusion"))
                powerToughness shouldBe PrintedPowerToughness(1, 1)
                keywords shouldBe persistentSetOf(Keyword.FLYING)
            }
            // A token is not a card, so it is not a registry entry — it is created on demand.
            MvpCards.definitions.keys.none { it.name == "Bird Illusion" } shouldBe true
        }

        "CR 602: Wellwisher's only ability costs the {T} symbol alone, from the battlefield" {
            val ability = wellwisher.activatedAbilities.single()
            ability.cost shouldContainExactly persistentListOf(AbilityCost.TapSelf)
            ability.zoneScope shouldBe AbilityZoneScope.Battlefield
            ability.librarySearch.shouldBeNull()
        }

        "CR 702.34, CR 702.140: only Gnaw to the Bone and Spinewoods Paladin are castable from elsewhere" {
            gnawToTheBone.castingPermissions shouldContainExactly
                listOf(CastingPermission.Flashback(ManaCost.parse("{2}{G}")))
            spinewoodsPaladin.castingPermissions shouldContainExactly
                listOf(CastingPermission.Plot(ManaCost.parse("{3}{G}")))
            listOf(
                gutShot,
                galvanicBlast,
                breathWeapon,
                endTheFestivities,
                healerOfTheGlade,
                outlawMedic,
                spiritLink,
                unionOfTheThirdPath,
                wellwisher,
                murmuringMystic,
            ).forEach { it.castingPermissions.shouldBeEmpty() }
        }

        "CR 605.1a: none of the twelve is a mana source, and only Wellwisher has an activated ability" {
            val packet: List<CardDefinition> =
                listOf(
                    gutShot,
                    galvanicBlast,
                    breathWeapon,
                    endTheFestivities,
                    healerOfTheGlade,
                    outlawMedic,
                    spiritLink,
                    gnawToTheBone,
                    unionOfTheThirdPath,
                    spinewoodsPaladin,
                    wellwisher,
                    murmuringMystic,
                )
            packet.forEach { it.manaAbilities.shouldBeEmpty() }
            packet.filter { it != wellwisher }.forEach { it.activatedAbilities.shouldBeEmpty() }
            wellwisher.activatedAbilities.size shouldBe 1
        }
    })
