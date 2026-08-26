package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.CreatureType
import dev.mtgplay.core.card.Evasion
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.isCreatureType
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AffectedSet
import dev.mtgplay.core.definition.GraveyardCardRestriction
import dev.mtgplay.core.definition.GraveyardScope
import dev.mtgplay.core.definition.Magnitude
import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.StaticCondition
import dev.mtgplay.core.definition.TargetCount
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.mana.ManaCost
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentSetOf

/**
 * The keyword-tail cards (KeywordTailCards.kt) against their oracle text, freshly fetched from
 * Scryfall. Each is pinned at the printed line and at whichever detail of its text is *silently*
 * wrong if got wrong — which for three of the four is the keyword itself rather than the body.
 *
 * The rules behaviour behind them lives in `mtg-rules`' `KeywordTailSpec` (fixture objects only);
 * this suite pins the cards.
 */
class KeywordTailCardsSpec :
    StringSpec({

        // --- Toxin Analysis ---

        "CR 202/205: Toxin Analysis's printed line matches the oracle card" {
            with(toxinAnalysis.characteristics) {
                name shouldBe "Toxin Analysis"
                manaCost?.render() shouldBe "{B}"
                cardTypes shouldBe persistentSetOf(CardType.INSTANT)
                subtypes.shouldBeEmpty()
                powerToughness shouldBe null
            }
            toxinAnalysis.timing shouldBe TimingClass.INSTANT_SPEED
            // "Target creature", with no control clause — either player's.
            toxinAnalysis.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
        }

        "CR 701.50a: Investigate creates a Clue token with '{2}, Sacrifice this token: Draw a card'" {
            // The oracle line the packet brief omits. Its cost has **no** {T}, unlike the Food token's,
            // so a Clue may be cracked the turn it is created.
            with(clueToken.characteristics) {
                name shouldBe "Clue"
                cardTypes shouldBe persistentSetOf(CardType.ARTIFACT)
                subtypes shouldBe persistentSetOf(Subtype("Clue"))
                manaCost shouldBe null
                powerToughness shouldBe null
            }
            clueToken.activatedAbilities.single().cost shouldContainExactly
                listOf(AbilityCost.Mana(ManaCost.parse("{2}")), AbilityCost.SacrificeSelf)
        }

        // --- Rooftop Percher ---

        "CR 202/205/208: Rooftop Percher's printed line matches the oracle card" {
            with(rooftopPercher.characteristics) {
                name shouldBe "Rooftop Percher"
                manaCost?.render() shouldBe "{5}"
                cardTypes shouldBe persistentSetOf(CardType.CREATURE)
                // Its printed subtype line is Shapeshifter alone; changeling supplies the rest.
                subtypes shouldBe persistentSetOf(Subtype("Shapeshifter"))
                powerToughness shouldBe PrintedPowerToughness(power = 3, toughness = 3)
                keywords shouldBe persistentSetOf(Keyword.CHANGELING, Keyword.FLYING)
            }
        }

        "CR 702.73a: Rooftop Percher is every creature type, and no land or artifact type" {
            // Read off the printed characteristics, because CR 702.73a works in every zone — this is
            // the answer a library search or a graveyard read gets, with no battlefield object.
            with(rooftopPercher.characteristics) {
                hasSubtype(Subtype("Shapeshifter")) shouldBe true
                hasSubtype(Subtype("Elf")) shouldBe true
                hasSubtype(Subtype("Goblin")) shouldBe true
                hasSubtype(Subtype("Dragon")) shouldBe true
                hasSubtype(Subtype("Forest")) shouldBe false
                hasSubtype(Subtype("Mountain")) shouldBe false
                hasSubtype(Subtype("Food")) shouldBe false
            }
        }

        "CR 603.6a: Rooftop Percher's enters trigger exiles up to two target cards from graveyards" {
            val trigger = rooftopPercher.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            // "From graveyards" is ANY (either player's) and "cards" is unrestricted — both wider than
            // Blood Fountain's, and identical to Faerie Macabre's.
            trigger.targetSpec shouldBe
                TargetSpec.CardInGraveyard(
                    restriction = GraveyardCardRestriction.ANY_CARD,
                    scope = GraveyardScope.ANY,
                    count = TargetCount.UpTo(ROOFTOP_PERCHER_TARGETS),
                )
        }

        // --- Goblin Tomb Raider ---

        "CR 202/205/208: Goblin Tomb Raider's printed line matches the oracle card" {
            with(goblinTombRaider.characteristics) {
                name shouldBe "Goblin Tomb Raider"
                manaCost?.render() shouldBe "{R}"
                cardTypes shouldBe persistentSetOf(CardType.CREATURE)
                subtypes shouldBe persistentSetOf(Subtype("Goblin"), Subtype("Pirate"))
                powerToughness shouldBe PrintedPowerToughness(power = 1, toughness = 2)
                // The haste is *granted by its own conditional ability*, never printed.
                keywords.shouldBeEmpty()
            }
        }

        "CR 604.3: Goblin Tomb Raider's bonus is a conditional static ability on itself, not a trigger" {
            val effect = goblinTombRaider.staticContinuousEffects.single()
            effect.affects shouldBe AffectedSet.Self
            effect.condition shouldBe
                StaticCondition.YouControl(
                    filter = PermanentFilter(cardType = CardType.ARTIFACT, controlledByYou = true),
                )
            effect.grantedKeywords shouldBe persistentSetOf(Keyword.HASTE)
            effect.powerMod shouldBe Magnitude.Fixed(GOBLIN_TOMB_RAIDER_POWER_BONUS)
            // "+1/+0": the toughness half is untouched.
            effect.toughnessMod shouldBe Magnitude.Zero
            // It is a static ability and nothing else — encoding it as a trigger would be wrong.
            goblinTombRaider.triggeredAbilities.shouldBeEmpty()
        }

        // --- Gingerbrute ---

        "CR 202/205/208: Gingerbrute's printed line matches the oracle card" {
            with(gingerbrute.characteristics) {
                name shouldBe "Gingerbrute"
                manaCost?.render() shouldBe "{1}"
                // An *artifact* creature: it turns on Goblin Tomb Raider's condition.
                cardTypes shouldBe persistentSetOf(CardType.ARTIFACT, CardType.CREATURE)
                // Food is an artifact type and Golem a creature type — two card types, one type line.
                subtypes shouldBe persistentSetOf(Subtype("Food"), Subtype("Golem"))
                powerToughness shouldBe PrintedPowerToughness(power = 1, toughness = 1)
                keywords shouldBe persistentSetOf(Keyword.HASTE)
                // The evasion is granted by its own ability, never printed.
                evasions.shouldBeEmpty()
            }
        }

        "CR 205.3: Gingerbrute is a Golem but not a Food for changeling's purposes — the two type lists differ" {
            Subtype("Golem").isCreatureType() shouldBe true
            Subtype("Food").isCreatureType() shouldBe false
        }

        "CR 509.1b: Gingerbrute's first ability costs {1} and grants the haste evasion for the turn" {
            gingerbrute.activatedAbilities.first().cost shouldContainExactly
                listOf(AbilityCost.Mana(ManaCost.parse("{1}")))
            gingerbrute.activatedAbilities.first().targetSpec shouldBe TargetSpec.None
        }

        "CR 119.3: Gingerbrute's second ability is the Food ability — {2}, {T}, sacrifice: gain 3 life" {
            gingerbrute.activatedAbilities[1].cost shouldContainExactly
                listOf(
                    AbilityCost.Mana(ManaCost.parse("{2}")),
                    AbilityCost.TapSelf,
                    AbilityCost.SacrificeSelf,
                )
            GINGERBRUTE_LIFEGAIN shouldBe 3
        }

        // --- The changeling vocabulary gate ---

        "CR 205.3: every subtype the registry prints is classified as a creature type or not" {
            // The guarantee that keeps [Subtype.isCreatureType]'s loud failure unreachable in practice:
            // a new card printing an unclassified subtype breaks *this* test rather than a future game
            // in which a changeling silently became a Forest.
            val printed =
                MvpCards.definitions.values
                    .flatMap { it.characteristics.subtypes }
                    .toSet()
            val classified = CreatureType.CREATURE_TYPES + CreatureType.NON_CREATURE_TYPES

            (printed - classified).shouldBeEmpty()
        }

        "CR 205.3: no subtype word is classified as both a creature type and a non-creature type" {
            (CreatureType.CREATURE_TYPES intersect CreatureType.NON_CREATURE_TYPES).shouldBeEmpty()
        }

        "CR 702.73a: the pool's changelings are Rooftop Percher and Masked Vandal" {
            // Masked Vandal joined in `W9-F`; the pin stays a whole-registry set so a third cannot arrive
            // unnoticed, which is what makes CR 702.73a's every-creature-type reading testable at all.
            MvpCards.definitions.values
                .filter { Keyword.CHANGELING in it.characteristics.keywords }
                .map { it.characteristics.name }
                .sorted() shouldContainExactly listOf("Masked Vandal", "Rooftop Percher")
        }

        "CR 509.1b: no card in the pool *prints* the haste evasion — Gingerbrute grants its own" {
            MvpCards.definitions.values
                .filter { Evasion.BLOCKABLE_ONLY_BY_HASTE in it.characteristics.evasions }
                .shouldBeEmpty()
        }
    })
