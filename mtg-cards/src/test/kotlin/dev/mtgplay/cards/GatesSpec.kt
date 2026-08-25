package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.AsEntersColorChoice
import dev.mtgplay.core.definition.EntersTapped
import dev.mtgplay.core.definition.GraveyardCardRestriction
import dev.mtgplay.core.definition.GraveyardScope
import dev.mtgplay.core.definition.LibraryLook
import dev.mtgplay.core.definition.LibraryLookMode
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ManaAbilityCost
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.mana.manaType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * The printed half of the `W8-A` cards, checked line by line against the oracle text (CR 201–205): the
 * three colour-choosing Gates (Gates.kt), Bender's Waterskin (BendersWaterskin.kt), and the two utility
 * lands Mortuary Mire and Conduit Pylons (NonbasicLands.kt).
 *
 * Engine-driven behaviour lives next door in `mtg-rules`: the CR 614.12 choice on a played land and the
 * production that reads it back (`AsEntersColorLandSpec`), surveil (`SurveilSpec`), the "you may" that
 * wraps a whole trigger (`OptionalTriggerSpec`), and the CR 502.2 untap-step modification
 * (`OtherPlayersUntapStepSpec`). Those are fixture-driven by the rule that an engine test never names a
 * card; this file is where the *card* is held to its printed line.
 */
class GatesSpec :
    StringSpec({

        /** The cycle, paired with its printed name and the colour its ability fixes. */
        val cycle =
            listOf(
                Triple(citadelGate, "Citadel Gate", Color.WHITE),
                Triple(cliffgate, "Cliffgate", Color.RED),
                Triple(manorGate, "Manor Gate", Color.GREEN),
            )

        "CR 205: every Gate is a colourless-cost Land — Gate with no supertype and no keyword" {
            cycle.forEach { (definition, name, _) ->
                with(definition.characteristics) {
                    this.name shouldBe name
                    // A land has no mana cost (CR 202.1) — it is played, never cast (CR 305.1).
                    manaCost.shouldBeNull()
                    supertypes shouldBe persistentSetOf<Supertype>()
                    cardTypes shouldBe persistentSetOf(CardType.LAND)
                    subtypes shouldBe persistentSetOf(Subtype("Gate"))
                    powerToughness.shouldBeNull()
                    keywords shouldBe persistentSetOf<Keyword>()
                }
            }
        }

        "CR 614.1c: \"This land enters tapped\" is printed on all three, unconditionally" {
            cycle.forEach { (definition, _, _) -> definition.entersTapped shouldBe EntersTapped.Always }
        }

        "CR 614.12: each Gate chooses a colour as it enters, excluding its own" {
            cycle.forEach { (definition, _, own) ->
                definition.asEntersColorChoice shouldBe AsEntersColorChoice(excluding = own)
            }
        }

        "CR 605.1a: one ability — \"{T}: Add <its colour> or one mana of the chosen color\"" {
            cycle.forEach { (definition, _, own) ->
                definition.manaAbilities shouldBe
                    persistentListOf(
                        ManaAbility(
                            options = persistentListOf(own.manaType()),
                            cost = persistentListOf(ManaAbilityCost.TapSelf),
                            includesChosenColor = true,
                        ),
                    )
                // The whole card is those three clauses: no activated ability, no static, no trigger.
                definition.activatedAbilities.shouldBeEmpty()
                definition.triggeredAbilities.shouldBeEmpty()
                definition.staticContinuousEffects.shouldBeEmpty()
            }
        }

        "CR 205.3i: Basilisk Gate is a Gate too, and prints neither of the cycle's two clauses" {
            basiliskGate.characteristics.subtypes shouldBe persistentSetOf(Subtype("Gate"))
            basiliskGate.asEntersColorChoice.shouldBeNull()
            basiliskGate.entersTapped shouldBe EntersTapped.Never
        }

        "CR 613.11: Bender's Waterskin is a {3} Artifact that untaps in each other player's untap step" {
            with(bendersWaterskin.characteristics) {
                name shouldBe "Bender's Waterskin"
                manaCost shouldBe ManaCost.parse("{3}")
                cardTypes shouldBe persistentSetOf(CardType.ARTIFACT)
                subtypes shouldBe persistentSetOf<Subtype>()
                powerToughness.shouldBeNull()
            }
            bendersWaterskin.untapsInEachOtherPlayersUntapStep.shouldBeTrue()
            // "{T}: Add one mana of any color" — five options, the default {T} cost, one mana.
            bendersWaterskin.manaAbilities shouldBe
                persistentListOf(
                    ManaAbility(
                        persistentListOf(
                            ManaType.WHITE,
                            ManaType.BLUE,
                            ManaType.BLACK,
                            ManaType.RED,
                            ManaType.GREEN,
                        ),
                    ),
                )
            bendersWaterskin.triggeredAbilities.shouldBeEmpty()
            bendersWaterskin.activatedAbilities.shouldBeEmpty()
        }

        "CR 502.2: no other card in the registry claims the untap-step modification" {
            MvpCards.definitions.values
                .filter { it.untapsInEachOtherPlayersUntapStep }
                .map { it.characteristics.name } shouldBe listOf("Bender's Waterskin")
        }

        "CR 603.2: Mortuary Mire's enters trigger is optional and targets a creature card in your graveyard" {
            with(mortuaryMire.characteristics) {
                name shouldBe "Mortuary Mire"
                manaCost.shouldBeNull()
                cardTypes shouldBe persistentSetOf(CardType.LAND)
                subtypes shouldBe persistentSetOf<Subtype>()
            }
            mortuaryMire.entersTapped shouldBe EntersTapped.Always
            mortuaryMire.manaAbilities shouldBe persistentListOf(ManaAbility(persistentListOf(ManaType.BLACK)))

            val trigger = mortuaryMire.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            // "you may" wraps the whole instruction, so it gates the effect rather than part of it.
            trigger.optional.shouldBeTrue()
            trigger.targetSpec shouldBe
                TargetSpec.CardInGraveyard(
                    restriction = GraveyardCardRestriction.CREATURE,
                    scope = GraveyardScope.YOURS,
                )
            // The Mire itself is not a "you may" on a clause — the effect is the ability's own.
            trigger.libraryLook.shouldBeNull()
            trigger.optionalDraw.shouldBeNull()
        }

        "CR 701.44a: Conduit Pylons is a Desert whose enters trigger surveils 1" {
            with(conduitPylons.characteristics) {
                name shouldBe "Conduit Pylons"
                manaCost.shouldBeNull()
                cardTypes shouldBe persistentSetOf(CardType.LAND)
                subtypes shouldBe persistentSetOf(Subtype("Desert"))
            }
            // No enters-tapped clause is printed on it, unlike every other land in this packet.
            conduitPylons.entersTapped shouldBe EntersTapped.Never

            val trigger = conduitPylons.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            trigger.libraryLook shouldBe LibraryLook(LibraryLookMode.Surveil(1))
            trigger.optional.shouldBeFalse()
            trigger.targetSpec shouldBe TargetSpec.None
        }

        "CR 602.1: Conduit Pylons prints two mana abilities — a free {C} and a {1}, {T} filter" {
            conduitPylons.manaAbilities shouldBe
                persistentListOf(
                    ManaAbility(persistentListOf(ManaType.COLORLESS)),
                    ManaAbility(
                        options =
                            persistentListOf(
                                ManaType.WHITE,
                                ManaType.BLUE,
                                ManaType.BLACK,
                                ManaType.RED,
                                ManaType.GREEN,
                            ),
                        cost =
                            persistentListOf(
                                ManaAbilityCost.Mana(ManaCost.parse("{1}")),
                                ManaAbilityCost.TapSelf,
                            ),
                    ),
                )
            // Neither is restricted to one activation each turn: both spend the source by tapping it.
            conduitPylons.manaAbilities.none { it.oncePerTurn }.shouldBeTrue()
        }

        "ADR-003: the six cards of this packet are all registered under their printed names" {
            listOf(citadelGate, cliffgate, manorGate, bendersWaterskin, mortuaryMire, conduitPylons)
                .forEach { definition ->
                    MvpCards.definitions[CardRef(definition.characteristics.name)] shouldBe definition
                }
        }
    })
