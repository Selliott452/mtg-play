package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.ChosenColorEffect
import dev.mtgplay.core.definition.InterveningIf
import dev.mtgplay.core.definition.OptionalAdditionalCost
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.TapRequirement
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.declaredClauses
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentSetOf

/**
 * The printed half of the prevention pair and the bargain creature, checked line by line against the
 * repo's Scryfall oracle text (CR 201–205, CR 702).
 *
 * Engine-driven behaviour — that a shield actually stops damage, that Flaring Pain turns protection's
 * prevention off, that a tap cost is enumerated without a summoning-sickness gate, and that an
 * unbargained Ouphe puts no ability on the stack — is not observable from a definition and lives in the
 * rules and acceptance modules.
 */
class PreventionAndBargainSpec :
    StringSpec({

        "CR 201-205: Flaring Pain is a {1}{R} instant that targets nothing" {
            with(flaringPain.characteristics) {
                name shouldBe "Flaring Pain"
                manaCost shouldBe ManaCost.parse("{1}{R}")
                supertypes shouldBe persistentSetOf<Supertype>()
                cardTypes shouldBe persistentSetOf(CardType.INSTANT)
                subtypes shouldBe persistentSetOf<Subtype>()
                powerToughness.shouldBeNull()
                // CR 202.2: no devoid, so the printed {R} makes it red — which matters, because a
                // Prismatic Strands on red prevents damage this card's own deck would deal.
                colors shouldBe setOf(Color.RED)
            }
            flaringPain.timing shouldBe TimingClass.INSTANT_SPEED
            // "Damage can't be prevented this turn" names nothing: CR 615.9 is global.
            flaringPain.targetSpec shouldBe TargetSpec.None
            // It makes no choice, so it carries no resolution clause of any kind.
            flaringPain.chosenColorEffect.shouldBeNull()
            flaringPain.declaredClauses.shouldBeEmpty()
        }

        "CR 702.34: Flaring Pain's flashback is {R} from the graveyard, exiling as it leaves the stack" {
            val flashback = flaringPain.castingPermissions.single()
            flashback shouldBe CastingPermission.Flashback(cost = ManaCost.parse("{R}"))
            flashback.cost shouldBe ManaCost.parse("{R}")
            flashback.source shouldBe CastSource.GRAVEYARD
            // CR 702.34e: "Then exile it" — the reminder text's second sentence.
            flashback.exilesOnLeaveStack shouldBe true
            // A mana-only flashback cost: neither non-mana component is printed.
            flashback.sacrifice.shouldBeNull()
            flashback.tap.shouldBeNull()
        }

        "CR 201-205: Prismatic Strands is a {2}{W} instant that targets nothing" {
            with(prismaticStrands.characteristics) {
                name shouldBe "Prismatic Strands"
                manaCost shouldBe ManaCost.parse("{2}{W}")
                cardTypes shouldBe persistentSetOf(CardType.INSTANT)
                subtypes shouldBe persistentSetOf<Subtype>()
                powerToughness.shouldBeNull()
            }
            prismaticStrands.timing shouldBe TimingClass.INSTANT_SPEED
            // "Prevent all damage that sources of the color of your choice would deal" targets nothing:
            // the shield covers every permanent and both players (CR 615.1).
            prismaticStrands.targetSpec shouldBe TargetSpec.None
        }

        "CR 609.4: Prismatic Strands chooses its colour as it resolves, not as it is cast" {
            prismaticStrands.chosenColorEffect shouldBe
                ChosenColorEffect.PreventDamageFromChosenColorThisTurn
            // The clause *is* the card, so it is the only one declared and the ordinary resolution is
            // the identity — checked here because a second clause would be an orchestration ambiguity.
            prismaticStrands.declaredClauses.size shouldBe 1
            // CR 614.12 is the other colour choice and is emphatically not this one: an instant never
            // enters the battlefield, so it declares no as-enters choice at all. (`W8-A` replaced the
            // boolean this test was written against with a declaration that also carries the excluded
            // colour, because "choose a color other than white" restricts the option list and a flag
            // could only say that a choice happens.)
            prismaticStrands.asEntersColorChoice shouldBe null
        }

        "CR 702.34c: Prismatic Strands' flashback cost is a tap, with no mana at all" {
            val flashback = prismaticStrands.castingPermissions.single()
            flashback.source shouldBe CastSource.GRAVEYARD
            flashback.exilesOnLeaveStack shouldBe true
            // "Flashback—Tap an untapped white creature you control": no mana is printed, so the mana
            // half is {0} — the shape Lava Dart's sacrifice flashback already takes.
            flashback.cost shouldBe ManaCost.parse("{0}")
            flashback.sacrifice.shouldBeNull()
            flashback.tap shouldBe
                TapRequirement(count = 1, color = Color.WHITE, cardType = CardType.CREATURE)
        }

        "CR 201-205: Troublemaker Ouphe is a {1}{G} 2/2 Ouphe" {
            with(troublemakerOuphe.characteristics) {
                name shouldBe "Troublemaker Ouphe"
                manaCost shouldBe ManaCost.parse("{1}{G}")
                cardTypes shouldBe persistentSetOf(CardType.CREATURE)
                subtypes shouldBe persistentSetOf(Subtype("Ouphe"))
                powerToughness?.power shouldBe 2
                powerToughness?.toughness shouldBe 2
            }
            troublemakerOuphe.timing shouldBe TimingClass.SORCERY_SPEED
            // The creature spell itself targets nothing; only its enters trigger does.
            troublemakerOuphe.targetSpec shouldBe TargetSpec.None
        }

        "CR 702.166a: Troublemaker Ouphe's bargain is an optional additional cost, not a kicker" {
            troublemakerOuphe.optionalAdditionalCost shouldBe OptionalAdditionalCost.Bargain
            // Kicker is the *mana* optional cost and is a different field; conflating them would price
            // the cast wrongly at CR 601.2f.
            troublemakerOuphe.kicker.shouldBeNull()
            // Bargain adds to the printed cost (CR 601.2b); it does not replace it (CR 118.9), so there
            // is no casting permission.
            troublemakerOuphe.castingPermissions.shouldBeEmpty()
            // Nor is it the mandatory additional cost: declining bargain is always legal.
            troublemakerOuphe.additionalCost.shouldBeNull()
        }

        "CR 603.4: the Ouphe's enters trigger is gated by an intervening if, and exiles a target" {
            val trigger = troublemakerOuphe.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            // "if it was bargained" — a CR 603.4 clause, so an unbargained Ouphe's ability never
            // triggers and never reaches the stack.
            trigger.interveningIf shouldBe InterveningIf.SourcePaidOptionalAdditionalCost
            // "target artifact or enchantment an opponent controls" — one restriction, because CR 205.1a
            // makes "artifact or enchantment" a disjunction over card types.
            trigger.targetSpec shouldBe
                TargetSpec.TargetPermanent(
                    PermanentRestriction.ARTIFACT_OR_ENCHANTMENT_AN_OPPONENT_CONTROLS,
                )
            // The exile is the ability's ordinary effect, not a post-resolution clause.
            trigger.declaredClauses.shouldBeEmpty()
        }
    })
