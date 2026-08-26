package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.GraveyardCardRestriction
import dev.mtgplay.core.definition.GraveyardScope
import dev.mtgplay.core.definition.ModeChoice
import dev.mtgplay.core.definition.TargetCount
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.ManaCost
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentSetOf

/**
 * The printed half of Call Damage Control, checked line by line against the repo's Scryfall oracle text
 * (CR 201–205, CR 700.2a) — the pool's first card whose mode arity is not one.
 *
 * Engine-driven behaviour — that two chosen modes produce two independent target requests, that the same
 * graveyard card may answer both (CR 115.3), and that one dead bullet does not fizzle the spell
 * (CR 608.2b) — is not observable from a definition and lives in `mtg-rules` (`MultiModeCastingSpec`).
 */
class CallDamageControlSpec :
    StringSpec({

        "CR 201-205: Call Damage Control is a {1}{G} sorcery that targets nothing of its own" {
            with(callDamageControl.characteristics) {
                name shouldBe "Call Damage Control"
                manaCost shouldBe ManaCost.parse("{1}{G}")
                supertypes shouldBe persistentSetOf<Supertype>()
                cardTypes shouldBe persistentSetOf(CardType.SORCERY)
                subtypes shouldBe persistentSetOf<Subtype>()
                powerToughness.shouldBeNull()
            }
            callDamageControl.timing shouldBe TimingClass.SORCERY_SPEED
            callDamageControl.castingPermissions.shouldBeEmpty()
            callDamageControl.optionalAdditionalCost.shouldBeNull()
            callDamageControl.additionalCost.shouldBeNull()
        }

        "CR 700.2a: 'Choose up to two' is a mode count of 0..2, and zero is a legal answer" {
            callDamageControl.modeChoice shouldBe ModeChoice.upTo(2)
            callDamageControl.modeChoice.minimum shouldBe 0
            callDamageControl.modeChoice.maximum shouldBe 2
        }

        "CR 700.2: the four printed bullets, in printed order" {
            callDamageControl.modes.map { it.text } shouldContainExactly
                listOf(
                    "Target artifact card.",
                    "Target creature card.",
                    "Target enchantment card.",
                    "Target land card.",
                )
        }

        "CR 404: every bullet names one card in *your* graveyard, and they differ only in the type" {
            val expected =
                listOf(
                    GraveyardCardRestriction.ARTIFACT,
                    GraveyardCardRestriction.CREATURE,
                    GraveyardCardRestriction.ENCHANTMENT,
                    GraveyardCardRestriction.LAND,
                )
            callDamageControl.modes.map { it.targetSpec } shouldContainExactly
                expected.map {
                    // CR 404: "from **your** graveyard" — an opponent's is never enumerated, so this is
                    // not graveyard hate. And each bullet takes exactly one card, which is what makes
                    // the per-mode target split derivable and the whole card testable.
                    TargetSpec.CardInGraveyard(
                        restriction = it,
                        scope = GraveyardScope.YOURS,
                        count = TargetCount.ONE,
                    )
                }
        }

        "CR 700.2: a mode count must be a real range — the declaration refuses nonsense" {
            shouldThrow<IllegalArgumentException> { ModeChoice(minimum = 2, maximum = 1) }
            shouldThrow<IllegalArgumentException> { ModeChoice(minimum = 0, maximum = 0) }
            shouldThrow<IllegalArgumentException> { ModeChoice(minimum = -1, maximum = 2) }
        }

        "CR 700.2a: every other modal card in the pool still prints 'Choose one'" {
            // The default is what keeps `FW-MODAL`'s four Blasts and two charms untouched by `W9-B`:
            // they never declared a count and they still choose exactly one.
            listOf(castIntoTheFire, thrabenCharm, blueElementalBlast, redElementalBlast)
                .map { it.modeChoice } shouldContainExactly List(4) { ModeChoice.EXACTLY_ONE }
        }
    })
