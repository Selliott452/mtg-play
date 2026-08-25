package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentSetOf

/**
 * The removal family (Removal.kt) against the oracle card: printed characteristics (CR 201–208) and
 * the declaration each printed clause maps onto — the "target &lt;permanent&gt;" spec (CR 115.1b)
 * with the noun each card prints, and Ancient Grudge's flashback (CR 702.34). Every card's
 * *behaviour* — destruction, exile, the indestructible exemption, the damage and lifegain riders — is
 * played end-to-end in `RemovalAcceptanceSpec`; this suite pins the data.
 */
class RemovalSpec :
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
                Expected(castDown, "Cast Down", "{1}{B}", CardType.INSTANT, TimingClass.INSTANT_SPEED),
                Expected(terminate, "Terminate", "{B}{R}", CardType.INSTANT, TimingClass.INSTANT_SPEED),
                Expected(
                    smashToSmithereens,
                    "Smash to Smithereens",
                    "{1}{R}",
                    CardType.INSTANT,
                    TimingClass.INSTANT_SPEED,
                ),
                Expected(ancientGrudge, "Ancient Grudge", "{1}{R}", CardType.INSTANT, TimingClass.INSTANT_SPEED),
                Expected(
                    scourFromExistence,
                    "Scour from Existence",
                    "{7}",
                    CardType.INSTANT,
                    TimingClass.INSTANT_SPEED,
                ),
                Expected(lastBreath, "Last Breath", "{1}{W}", CardType.INSTANT, TimingClass.INSTANT_SPEED),
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

        "CR 115.1b: each card's target spec carries exactly the noun its oracle line prints" {
            castDown.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.NONLEGENDARY_CREATURE)
            terminate.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
            smashToSmithereens.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.ARTIFACT)
            ancientGrudge.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.ARTIFACT)
            scourFromExistence.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.ANY_PERMANENT)
            lastBreath.targetSpec shouldBe
                TargetSpec.TargetPermanent(PermanentRestriction.CREATURE_POWER_2_OR_LESS)
        }

        "CR 702.34: Ancient Grudge is the only card in the family castable from elsewhere" {
            ancientGrudge.castingPermissions shouldContainExactly
                listOf(CastingPermission.Flashback(ManaCost.parse("{G}")))
            listOf(castDown, terminate, smashToSmithereens, scourFromExistence, lastBreath).forEach {
                it.castingPermissions.shouldBeEmpty()
            }
        }

        "CR 202.2: colours derive from the printed cost — Scour from Existence's {7} is colorless" {
            castDown.characteristics.colors shouldBe setOf(Color.BLACK)
            terminate.characteristics.colors shouldBe setOf(Color.BLACK, Color.RED)
            smashToSmithereens.characteristics.colors shouldBe setOf(Color.RED)
            // CR 202.2 reads the *face* cost only: Ancient Grudge is mono-red despite its green
            // flashback cost (which is what makes its colour *identity* red-green, CR 903.4 — a
            // distinction nothing in the engine reads yet).
            ancientGrudge.characteristics.colors shouldBe setOf(Color.RED)
            scourFromExistence.characteristics.colors.shouldBeEmpty()
            lastBreath.characteristics.colors shouldBe setOf(Color.WHITE)
        }

        "CR 605.1a: none of the family is a mana source or carries an ability of any kind" {
            listOf(castDown, terminate, smashToSmithereens, ancientGrudge, scourFromExistence, lastBreath)
                .forEach {
                    it.manaAbilities.shouldBeEmpty()
                    it.triggeredAbilities.shouldBeEmpty()
                    it.activatedAbilities.shouldBeEmpty()
                    it.staticContinuousEffects.shouldBeEmpty()
                    it.additionalCost.shouldBeNull()
                }
        }

        "every card in the family is registered in the pool under its printed name (CR 201)" {
            listOf(
                "Cast Down",
                "Terminate",
                "Smash to Smithereens",
                "Ancient Grudge",
                "Scour from Existence",
                "Last Breath",
            ).forEach { name ->
                MvpCards.definitions
                    .getValue(CardRef(name))
                    .characteristics.name shouldBe name
            }
            // Raze landed with `P-ABILSOURCE`, in LandDestruction.kt rather than here: its target
            // noun is a land (CR 305), so it belongs to the gauntlet's land destruction family.
            (CardRef("Raze") in MvpCards.definitions) shouldBe true
            // Cryoshatter landed with `W8-C`, in BurnAndRemoval.kt rather than here: the two trigger
            // conditions this file recorded as missing — a permanent becoming tapped (CR 701.20a) and a
            // permanent being dealt damage (CR 120.3d) — are now `TriggerCondition` members with a
            // detection site each, so the diagnosis this line used to carry is discharged.
            (CardRef("Cryoshatter") in MvpCards.definitions) shouldBe true
        }
    })
