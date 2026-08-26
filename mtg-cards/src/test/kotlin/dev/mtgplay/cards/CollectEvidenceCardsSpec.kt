package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.EachOpponentSacrifices
import dev.mtgplay.core.definition.InterveningIf
import dev.mtgplay.core.definition.OptionalAdditionalCost
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.SacrificeNarrowing
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.declaredClauses
import dev.mtgplay.core.mana.ManaCost
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.collections.immutable.persistentSetOf

/**
 * The printed half of the two **collect evidence** cards, checked line by line against the repo's
 * Scryfall oracle text (CR 201–208, CR 701.60a).
 *
 * Engine-driven behaviour — that the announcement is gated on the graveyard's *total* rather than its
 * emptiness, that the selection accepts any summing subset and rejects a short one, and that the
 * greatest-power narrowing filters without collapsing — is not observable from a definition and lives in
 * `mtg-rules` (`CollectEvidenceSpec`).
 */
class CollectEvidenceCardsSpec :
    StringSpec({

        "CR 201-208: Vitu-Ghazi Inspector is a {1}{G} Elf Detective 1/3 with reach" {
            with(vituGhaziInspector.characteristics) {
                name shouldBe "Vitu-Ghazi Inspector"
                manaCost shouldBe ManaCost.parse("{1}{G}")
                supertypes shouldBe persistentSetOf<Supertype>()
                cardTypes shouldBe persistentSetOf(CardType.CREATURE)
                // CR 205.3m: both printed subtypes, and both are creature types.
                subtypes shouldBe persistentSetOf(Subtype("Elf"), Subtype("Detective"))
                powerToughness shouldBe PrintedPowerToughness(power = 1, toughness = 3)
                // CR 702.17: reach is printed on the card, not granted by the evidence.
                keywords shouldBe persistentSetOf(Keyword.REACH)
            }
            vituGhaziInspector.timing shouldBe TimingClass.SORCERY_SPEED
            // A creature spell names nothing as it is cast; only its enters trigger targets.
            vituGhaziInspector.targetSpec shouldBe TargetSpec.None
            vituGhaziInspector.declaredClauses.shouldBeEmpty()
        }

        "CR 701.60a: Vitu-Ghazi Inspector's additional cost is an optional collect evidence 6" {
            vituGhaziInspector.optionalAdditionalCost shouldBe OptionalAdditionalCost.CollectEvidence(6)
            // The keyword is *additional*, not an alternative cost: nothing replaces the printed {1}{G}.
            vituGhaziInspector.castingPermissions.shouldBeEmpty()
            vituGhaziInspector.additionalCost.shouldBeNull()
        }

        "CR 603.4: the Inspector's enters trigger is gated by an intervening if, not by its effect" {
            val trigger = vituGhaziInspector.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            // "if evidence was collected" is the CR 603.4 two-check clause reading the cast record's
            // linked information — an Inspector cast without evidence does not trigger at all, so no
            // ability goes on the stack and no priority round opens for a response.
            trigger.interveningIf shouldBe InterveningIf.SourcePaidOptionalAdditionalCost
            // "put a +1/+1 counter on target creature": one target, any creature, no control clause.
            trigger.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
        }

        "CR 201-205: Extract a Confession is a {1}{B} sorcery that targets nothing" {
            with(extractAConfession.characteristics) {
                name shouldBe "Extract a Confession"
                manaCost shouldBe ManaCost.parse("{1}{B}")
                supertypes shouldBe persistentSetOf<Supertype>()
                cardTypes shouldBe persistentSetOf(CardType.SORCERY)
                subtypes shouldBe persistentSetOf<Subtype>()
                powerToughness.shouldBeNull()
            }
            extractAConfession.timing shouldBe TimingClass.SORCERY_SPEED
            // CR 701.17a: an edict names nothing — which is why it answers a hexproof Slippery Bogle.
            extractAConfession.targetSpec shouldBe TargetSpec.None
            extractAConfession.triggeredAbilities.shouldBeEmpty()
        }

        "CR 701.60a: Extract a Confession collects the same evidence 6, and it is optional" {
            extractAConfession.optionalAdditionalCost shouldBe OptionalAdditionalCost.CollectEvidence(6)
            extractAConfession.additionalCost.shouldBeNull()
        }

        "CR 701.17a: the printed 'instead' is one clause with two narrowings, chosen by the cost" {
            extractAConfession.declaredClauses.size shouldBe 1
            extractAConfession.eachOpponentSacrifices shouldBe
                EachOpponentSacrifices(
                    cardType = CardType.CREATURE,
                    // "each opponent sacrifices a creature of their choice"
                    narrowing = SacrificeNarrowing.ANY,
                    // "instead ... a creature with the greatest power among creatures they control"
                    narrowingWhenOptionalCostPaid = SacrificeNarrowing.GREATEST_POWER,
                )
            // The two are genuinely different lines, which is the whole of what the six mana value buys.
            extractAConfession.eachOpponentSacrifices?.narrowing shouldNotBe
                extractAConfession.eachOpponentSacrifices?.narrowingWhenOptionalCostPaid
        }

        "a card printing no such cost carries one narrowing, so it is never narrowed by an absent cost" {
            val plainEdict = EachOpponentSacrifices(cardType = CardType.CREATURE)
            plainEdict.narrowing shouldBe SacrificeNarrowing.ANY
            plainEdict.narrowingWhenOptionalCostPaid shouldBe SacrificeNarrowing.ANY
        }

        "CR 701.60a: collect evidence N requires a positive N" {
            shouldThrow<IllegalArgumentException> { OptionalAdditionalCost.CollectEvidence(0) }
        }
    })
