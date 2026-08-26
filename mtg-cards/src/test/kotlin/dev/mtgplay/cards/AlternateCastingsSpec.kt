package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentSetOf

/**
 * The printed half of `W9-G`'s two alternate castings against the oracle cards (CR 201–205, CR 702):
 * type lines, mana costs, and the exact shape of each keyword's declaration.
 *
 * Their *behaviour* — the 3/3 that gains 3 life, the dig that walks past lands, the free cast, the seeded
 * bottoming — is played end-to-end through the real engine in the acceptance module's
 * `AlternateCastingAcceptanceSpec`. Nothing here asserts a game outcome.
 */
class AlternateCastingsSpec :
    StringSpec({

        "CR 201 / CR 301: Boulderbranch Golem is a {7} artifact creature — Golem 6/5" {
            with(boulderbranchGolem.characteristics) {
                name shouldBe "Boulderbranch Golem"
                manaCost?.render() shouldBe "{7}"
                supertypes shouldBe persistentSetOf<Supertype>()
                cardTypes shouldBe persistentSetOf(CardType.ARTIFACT, CardType.CREATURE)
                subtypes shouldBe persistentSetOf(Subtype("Golem"))
                powerToughness shouldBe PrintedPowerToughness(power = 6, toughness = 5)
                // CR 202.2: the printed cost is colourless, which is the half prototype replaces.
                colors shouldBe emptySet<Color>()
                manaValue shouldBe 7
            }
            boulderbranchGolem.timing shouldBe TimingClass.SORCERY_SPEED
            boulderbranchGolem.targetSpec shouldBe TargetSpec.None
        }

        "CR 702.160a: Boulderbranch Golem's prototype is {3}{G} — 3/3, cast from the hand" {
            val prototype = boulderbranchGolem.castingPermissions.single()
            prototype shouldBe CastingPermission.Prototype(cost = ManaCost.parse("{3}{G}"), power = 3, toughness = 3)
            // CR 601.2f / CR 118.9: an alternative cost is cast from the hand and replaces the printed
            // cost; it is offered at a priority window beside the normal cast (ADR-005).
            prototype.source shouldBe CastSource.HAND
            prototype.offeredAtPriority shouldBe true
            // CR 718.3b: the colour half needs no declaration — it falls out of the prototyped cost.
            prototype.cost.colors shouldBe setOf(Color.GREEN)
            prototype.cost.manaValue shouldBe 4
        }

        "CR 603.6a: Boulderbranch Golem's only ability is an enters trigger with no target" {
            val trigger = boulderbranchGolem.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            trigger.zoneScope shouldBe TriggerZoneScope.Battlefield
            trigger.targetSpec shouldBe TargetSpec.None
            boulderbranchGolem.activatedAbilities.shouldBeEmpty()
            boulderbranchGolem.manaAbilities.shouldBeEmpty()
        }

        "CR 201 / CR 301: Maelstrom Colossus is an {8} artifact creature — Golem 7/7 whose whole text is cascade" {
            with(maelstromColossus.characteristics) {
                name shouldBe "Maelstrom Colossus"
                manaCost?.render() shouldBe "{8}"
                supertypes shouldBe persistentSetOf<Supertype>()
                cardTypes shouldBe persistentSetOf(CardType.ARTIFACT, CardType.CREATURE)
                subtypes shouldBe persistentSetOf(Subtype("Golem"))
                powerToughness shouldBe PrintedPowerToughness(power = 7, toughness = 7)
                // The number cascade's "lesser mana value" comparison is made against (CR 702.85a).
                manaValue shouldBe 8
            }
            maelstromColossus.timing shouldBe TimingClass.SORCERY_SPEED
            maelstromColossus.targetSpec shouldBe TargetSpec.None
        }

        "CR 702.85a: cascade is declared as the bare keyword — nothing on the card parameterises it" {
            maelstromColossus.cascade shouldBe true
            // It is *not* a casting permission of this card: cascade grants a free cast of some **other**
            // card, so the Colossus declares no permission of its own and is castable only for {8}.
            maelstromColossus.castingPermissions.shouldBeEmpty()
            maelstromColossus.triggeredAbilities.shouldBeEmpty()
            maelstromColossus.activatedAbilities.shouldBeEmpty()
        }

        "CR 702.85a: cascade is off by default, so no other card in the registry fires it" {
            MvpCards.definitions
                .filterValues { it is SpellDefinition && it.cascade }
                .keys shouldBe setOf(CardRef("Maelstrom Colossus"))
        }
    })
