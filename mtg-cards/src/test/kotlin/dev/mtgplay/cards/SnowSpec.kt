package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldNotBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * The printed half of the snow packet against the oracle cards (CR 201–205): the three Snow-Covered
 * basics' two-supertype type lines (CR 205.4a) and authored CR 305.6 mana abilities, and Skred's
 * cost, timing, and target spec.
 *
 * Skred's *behaviour* — the count varying with the battlefield, the fizzle, the refusal to go to the
 * face — is played end-to-end in the acceptance module's `SnowAcceptanceSpec`.
 */
class SnowSpec :
    StringSpec({

        "CR 205.4a: each Snow-Covered basic is Basic and Snow, with its land type — not its name — as subtype" {
            val basics =
                mapOf(
                    snowCoveredIsland to Triple("Snow-Covered Island", "Island", ManaType.BLUE),
                    snowCoveredMountain to Triple("Snow-Covered Mountain", "Mountain", ManaType.RED),
                    snowCoveredPlains to Triple("Snow-Covered Plains", "Plains", ManaType.WHITE),
                )
            basics.forEach { (definition, expected) ->
                val (name, subtype, produces) = expected
                with(definition.characteristics) {
                    this.name shouldBe name
                    manaCost.shouldBeNull()
                    supertypes shouldBe persistentSetOf(Supertype.BASIC, Supertype.SNOW)
                    cardTypes shouldBe persistentSetOf(CardType.LAND)
                    // CR 205.3i's name-equals-subtype shorthand does not hold: the subtype is the bare land type.
                    subtypes shouldBe persistentSetOf(Subtype(subtype))
                    powerToughness.shouldBeNull()
                }
                // CR 305.6, authored explicitly per the P2.2 decision recorded in BasicLands.kt.
                definition.manaAbilities shouldBe persistentListOf(ManaAbility(persistentListOf(produces)))
                // CR 110.5a: no snow basic prints an enters-tapped clause.
                definition.entersTapped.shouldBeFalse()
            }
        }

        "CR 305.1: a Snow-Covered basic is played, not cast — it is a CardDefinition and never a SpellDefinition" {
            listOf(snowCoveredIsland, snowCoveredMountain, snowCoveredPlains).forEach { definition ->
                definition.shouldNotBeInstanceOf<SpellDefinition>()
            }
        }

        "CR 205.4a: the plain basics are untouched by this packet — Island is Basic but not Snow" {
            island.characteristics.supertypes shouldBe persistentSetOf(Supertype.BASIC)
            mountain.characteristics.supertypes shouldBe persistentSetOf(Supertype.BASIC)
            plains.characteristics.supertypes shouldBe persistentSetOf(Supertype.BASIC)
        }

        "CR 202 / CR 115.1a: Skred is a {R} instant that targets a creature and nothing else" {
            with(skred.characteristics) {
                name shouldBe "Skred"
                manaCost?.render() shouldBe "{R}"
                supertypes shouldBe persistentSetOf<Supertype>()
                cardTypes shouldBe persistentSetOf(CardType.INSTANT)
                subtypes shouldBe persistentSetOf<Subtype>()
                powerToughness.shouldBeNull()
            }
            skred.timing shouldBe TimingClass.INSTANT_SPEED
            skred.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
        }

        "the four snow cards are registered under their printed names (CR 201)" {
            val registered: Map<CardRef, CardDefinition> = MvpCards.definitions
            listOf(
                "Snow-Covered Island" to snowCoveredIsland,
                "Snow-Covered Mountain" to snowCoveredMountain,
                "Snow-Covered Plains" to snowCoveredPlains,
                "Skred" to skred,
            ).forEach { (name, definition) -> registered[CardRef(name)] shouldBe definition }
        }

        "no snow card in the gauntlet prints an ability of its own — the supertype grants nothing" {
            listOf(snowCoveredIsland, snowCoveredMountain, snowCoveredPlains).forEach { definition ->
                definition.triggeredAbilities.shouldBeEmpty()
                definition.activatedAbilities.shouldBeEmpty()
                definition.staticContinuousEffects.shouldBeEmpty()
            }
        }
    })
