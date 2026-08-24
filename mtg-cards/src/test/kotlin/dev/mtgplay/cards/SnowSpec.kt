package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldNotBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * The printed half of the snow packet against the oracle cards (CR 201–205): the three Snow-Covered
 * basics' two-supertype type lines (CR 205.4a) and authored CR 305.6 mana abilities, the two snow duals'
 * Snow-but-not-Basic line and enters-tapped clause, and Skred's cost, timing, and target spec.
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

        "CR 205.4b: a snow dual is Snow but NOT Basic, and carries one mana ability per land type" {
            val duals =
                mapOf(
                    glacialFloodplain to
                        Triple(
                            "Glacial Floodplain",
                            listOf("Plains", "Island"),
                            listOf(ManaType.WHITE, ManaType.BLUE),
                        ),
                    volatileFjord to
                        Triple(
                            "Volatile Fjord",
                            listOf("Island", "Mountain"),
                            listOf(ManaType.BLUE, ManaType.RED),
                        ),
                )
            duals.forEach { (definition, expected) ->
                val (name, subtypes, produces) = expected
                with(definition.characteristics) {
                    this.name shouldBe name
                    manaCost.shouldBeNull()
                    // CR 205.4b: basic land *types* do not make a card basic — only the Snow supertype is printed.
                    supertypes shouldBe persistentSetOf(Supertype.SNOW)
                    cardTypes shouldBe persistentSetOf(CardType.LAND)
                    this.subtypes shouldBe subtypes.map { Subtype(it) }.toSet()
                    powerToughness.shouldBeNull()
                }
                // CR 305.6: two separate intrinsic abilities, one per land type — the Idyllic Beachfront shape.
                definition.manaAbilities shouldBe produces.map { ManaAbility(persistentListOf(it)) }
                // CR 614.1c: "This land enters tapped" is the whole of its printed rules text.
                definition.entersTapped.shouldBeTrue()
            }
        }

        "CR 305.1: every snow land is played, not cast — a CardDefinition and never a SpellDefinition" {
            snowLands.forEach { definition -> definition.shouldNotBeInstanceOf<SpellDefinition>() }
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
            skred.targetSpec shouldBe TargetSpec.TargetCreature
        }

        "the six snow cards are registered under their printed names (CR 201)" {
            val registered: Map<CardRef, CardDefinition> = MvpCards.definitions
            listOf(
                "Snow-Covered Island" to snowCoveredIsland,
                "Snow-Covered Mountain" to snowCoveredMountain,
                "Snow-Covered Plains" to snowCoveredPlains,
                "Glacial Floodplain" to glacialFloodplain,
                "Volatile Fjord" to volatileFjord,
                "Skred" to skred,
            ).forEach { (name, definition) -> registered[CardRef(name)] shouldBe definition }
        }

        "no snow land in the gauntlet prints an ability of its own — the supertype grants nothing" {
            snowLands.forEach { definition ->
                definition.triggeredAbilities.shouldBeEmpty()
                definition.activatedAbilities.shouldBeEmpty()
                definition.staticContinuousEffects.shouldBeEmpty()
            }
        }
    })

/** Every snow *land* in the gauntlet: the three Snow-Covered basics and the two snow duals. */
private val snowLands: List<CardDefinition> =
    listOf(snowCoveredIsland, snowCoveredMountain, snowCoveredPlains, glacialFloodplain, volatileFjord)
