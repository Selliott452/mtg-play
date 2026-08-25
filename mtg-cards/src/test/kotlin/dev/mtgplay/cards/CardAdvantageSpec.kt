package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.ChosenTypeReveal
import dev.mtgplay.core.definition.InterveningIf
import dev.mtgplay.core.definition.RevealedCardFilter
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.mana.ManaCost
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * The printed half of the card-advantage cards, read line by line off the Scryfall oracle text
 * (CR 201–205): Mulldrifter's evoke and its two enters triggers, Winding Way's resolution-time type
 * choice, and Reckless Impulse's play-from-exile grant.
 *
 * Engine-driven behaviour — that an evoked Mulldrifter is sacrificed and a hard-cast one is not, that
 * Winding Way's four revealed cards partition by the chosen type, and that Reckless Impulse's permission
 * survives exactly to the end of its controller's next turn — lives in the rules module, because none of
 * it is observable from a definition alone.
 */
class CardAdvantageSpec :
    StringSpec({

        "CR 302.1: Mulldrifter is a {4}{U} 2/2 Elemental with flying, cast at sorcery speed" {
            with(mulldrifter.characteristics) {
                name shouldBe "Mulldrifter"
                manaCost shouldBe ManaCost.parse("{4}{U}")
                cardTypes shouldBe persistentSetOf(CardType.CREATURE)
                subtypes shouldBe persistentSetOf(Subtype("Elemental"))
                powerToughness shouldBe PrintedPowerToughness(power = 2, toughness = 2)
                keywords shouldBe persistentSetOf(Keyword.FLYING)
            }
            mulldrifter.timing shouldBe TimingClass.SORCERY_SPEED
            mulldrifter.targetSpec shouldBe TargetSpec.None
        }

        "CR 702.74a: Mulldrifter's evoke is an alternative cost of {2}{U} cast from the hand" {
            val evoke = mulldrifter.castingPermissions.single()
            evoke shouldBe CastingPermission.Evoke(ManaCost.parse("{2}{U}"))
            // CR 118.9: it *replaces* the printed cost rather than adding to it, and it is a hand cast —
            // the same card is still castable normally, which is a second, distinct enumerated option.
            evoke.source shouldBe CastSource.HAND
            evoke.cost shouldBe ManaCost.parse("{2}{U}")
            // CR 702.34e is flashback's, not evoke's: an evoked spell resolves into a permanent normally
            // and reaches the graveyard by the sacrifice, not off the stack.
            evoke.exilesOnLeaveStack shouldBe false
            evoke.offeredAtPriority shouldBe true
        }

        "CR 702.74a: Mulldrifter has two enters triggers, and only the sacrifice has an intervening if" {
            val (draw, sacrifice) = mulldrifter.triggeredAbilities
            mulldrifter.triggeredAbilities.size shouldBe 2

            // "When this creature enters, draw two cards" — unconditional, so it fires on every entry.
            draw.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            draw.interveningIf.shouldBeNull()
            draw.targetSpec shouldBe TargetSpec.None

            // "When this permanent enters, if its evoke cost was paid, sacrifice it" — CR 603.4 gates the
            // *firing*, so a hard-cast Mulldrifter never puts this ability on the stack at all.
            sacrifice.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            sacrifice.interveningIf shouldBe InterveningIf.SourceWasEvoked
        }

        "CR 120.1: Mulldrifter's enters trigger draws exactly two" {
            MULLDRIFTER_DRAW shouldBe 2
        }

        "CR 307.1: Winding Way is a {1}{G} sorcery that targets nothing" {
            with(windingWay.characteristics) {
                name shouldBe "Winding Way"
                manaCost shouldBe ManaCost.parse("{1}{G}")
                cardTypes shouldBe persistentSetOf(CardType.SORCERY)
            }
            windingWay.timing shouldBe TimingClass.SORCERY_SPEED
            windingWay.targetSpec shouldBe TargetSpec.None
            windingWay.castingPermissions.shouldBeEmpty()
        }

        "CR 609.4: Winding Way chooses creature or land as it resolves, then reveals four" {
            windingWay.chosenTypeReveal shouldBe
                ChosenTypeReveal(
                    count = WINDING_WAY_REVEAL,
                    choices = persistentListOf(RevealedCardFilter.CREATURE_CARD, RevealedCardFilter.LAND_CARD),
                )
            WINDING_WAY_REVEAL shouldBe 4
            // CR 601.2b: it is *not* a modal card. A mode would be chosen while casting, a whole priority
            // round before the choice the card actually prints.
            windingWay.modes.shouldBeEmpty()
            // CR 701.16: it is not the "up to M" reveal either — that clause would offer keeping fewer.
            windingWay.libraryReveal.shouldBeNull()
            windingWay.libraryLook.shouldBeNull()
        }

        "CR 307.1: Reckless Impulse is a {1}{R} sorcery whose whole effect is the exile grant" {
            with(recklessImpulse.characteristics) {
                name shouldBe "Reckless Impulse"
                manaCost shouldBe ManaCost.parse("{1}{R}")
                cardTypes shouldBe persistentSetOf(CardType.SORCERY)
            }
            recklessImpulse.timing shouldBe TimingClass.SORCERY_SPEED
            recklessImpulse.targetSpec shouldBe TargetSpec.None
            RECKLESS_IMPULSE_EXILE shouldBe 2
        }

        "CR 118.5: Reckless Impulse declares no casting permission — the grant is not one" {
            // The permission it hands out belongs to the *exiled cards*, not to Reckless Impulse, and is
            // granted by its resolution rather than declared on any card. A CastingPermission here would
            // be a permission to cast Reckless Impulse itself, which is a different sentence entirely.
            recklessImpulse.castingPermissions.shouldBeEmpty()
            recklessImpulse.chosenTypeReveal.shouldBeNull()
            recklessImpulse.libraryReveal.shouldBeNull()
            recklessImpulse.triggeredAbilities.shouldBeEmpty()
        }
    })
