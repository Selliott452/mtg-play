package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.CostReduction
import dev.mtgplay.core.definition.GraveyardCardRestriction
import dev.mtgplay.core.definition.LibrarySearchDestination
import dev.mtgplay.core.definition.LibrarySearchFilter
import dev.mtgplay.core.definition.LibrarySearchSearcher
import dev.mtgplay.core.definition.OwnerLibraryPlacement
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.declaredClauses
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaCost
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentSetOf

/**
 * The printed half of `W9-F`'s three encoded cards against the oracle text (CR 201–205): each card's
 * type line and mana cost, and — for the three seams the packet opened — the *declaration* that makes
 * the printed sentence mean what it says rather than something adjacent to it.
 *
 * Each card's assertions are written against the exact phrase they enforce, because in all three cases
 * the plausible wrong encoding is a **strictly different card**, not a cosmetic slip:
 *
 * - Cleansing Wildfire searching *the caster's* library instead of the target's controller's;
 * - Masked Vandal exiling its target whether or not a creature card was exiled;
 * - Deem Inferior letting the *caster* pick the depth.
 */
class SearchLkiAndGatedClauseCardsSpec :
    StringSpec({

        // ---------------------------------------------------------------- Cleansing Wildfire

        "CR 201/205: Cleansing Wildfire is a {1}{R} sorcery that targets any land (CR 305)" {
            with(cleansingWildfire.characteristics) {
                name shouldBe "Cleansing Wildfire"
                manaCost shouldBe ManaCost.parse("{1}{R}")
                cardTypes shouldBe persistentSetOf(CardType.SORCERY)
                subtypes shouldBe persistentSetOf<Subtype>()
                powerToughness.shouldBeNull()
            }
            cleansingWildfire.timing shouldBe TimingClass.SORCERY_SPEED
            // "Destroy target *land*" — not "target land an opponent controls"; aiming it at your own
            // Bridge is the line the gauntlet actually plays.
            cleansingWildfire.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.LAND)
        }

        "CR 701.18a: 'its controller may search their library' — the searcher is the *target's* controller" {
            val search = cleansingWildfire.librarySearch.shouldNotBeNull()
            // The whole card. `CONTROLLER` here would be a free Rampant Growth for the caster.
            search.searcher shouldBe LibrarySearchSearcher.TARGET_CONTROLLER
            // "for a basic land card" — the Basic supertype, so a Bridge or a Landscape is no find.
            search.find shouldBe LibrarySearchFilter.BASIC_LAND_CARD
            // "put it onto the battlefield tapped" — CR 110.5b, fixed by the instruction.
            search.destination shouldBe LibrarySearchDestination.BATTLEFIELD_TAPPED
            // "**may** search" — CR 601.3b, whose decline index is what suppresses the shuffle.
            search.optional shouldBe true
        }

        "CR 121.1: 'Draw a card.' is the last sentence, so it is a clause tail rather than the effect" {
            // A draw folded into the resolution effect would happen *before* the search and its shuffle
            // — a different card off a different library order, and a replay-visible difference because
            // the shuffle consumes seeded entropy (ADR-006).
            cleansingWildfire.librarySearch
                .shouldNotBeNull()
                .thenDraw shouldBe 1
        }

        "CR 608.2c: Cleansing Wildfire declares exactly one clause, the search" {
            cleansingWildfire.declaredClauses.size shouldBe 1
        }

        // ---------------------------------------------------------------- Masked Vandal

        "CR 201/205: Masked Vandal is a {1}{G} 1/3 Shapeshifter with changeling" {
            with(maskedVandal.characteristics) {
                name shouldBe "Masked Vandal"
                manaCost shouldBe ManaCost.parse("{1}{G}")
                cardTypes shouldBe persistentSetOf(CardType.CREATURE)
                subtypes shouldBe persistentSetOf(Subtype("Shapeshifter"))
                powerToughness shouldBe PrintedPowerToughness(power = 1, toughness = 3)
                // CR 702.73a: a characteristic-defining ability, so it functions in every zone.
                keywords shouldBe persistentSetOf(Keyword.CHANGELING)
            }
            maskedVandal.timing shouldBe TimingClass.SORCERY_SPEED
            maskedVandal.targetSpec shouldBe TargetSpec.None
        }

        "CR 603.3d: the enters trigger targets an artifact or enchantment an *opponent* controls" {
            val trigger = maskedVandal.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            trigger.targetSpec shouldBe
                TargetSpec.TargetPermanent(PermanentRestriction.ARTIFACT_OR_ENCHANTMENT_AN_OPPONENT_CONTROLS)
            // Not a "may" over the whole ability: the target is chosen as it goes on the stack whatever
            // the controller later decides about their graveyard.
            trigger.optional shouldBe false
        }

        "CR 404/608.2c: 'you may exile a creature card … If you do' is a gate carrying the gated effect" {
            val trigger = maskedVandal.triggeredAbilities.single()
            val gate = trigger.optionalGraveyardExileGate.shouldNotBeNull()
            // "a **creature** card", not any card — the filter is the printed noun.
            gate.restriction shouldBe GraveyardCardRestriction.CREATURE
            // The ordinary effect slot is deliberately empty: a clause runs *after* the ordinary effect,
            // so an exile-the-target effect declared there would fire before anybody was asked.
            trigger.declaredClauses.size shouldBe 1
        }

        // ---------------------------------------------------------------- Deem Inferior

        "CR 201/205: Deem Inferior is a {3}{U} sorcery targeting a nonland permanent" {
            with(deemInferior.characteristics) {
                name shouldBe "Deem Inferior"
                manaCost shouldBe ManaCost.parse("{3}{U}")
                cardTypes shouldBe persistentSetOf(CardType.SORCERY)
                powerToughness.shouldBeNull()
            }
            deemInferior.timing shouldBe TimingClass.SORCERY_SPEED
            // CR 205.1a: a permanent has every card type printed on it, so an artifact land is excluded.
            deemInferior.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.NONLAND_PERMANENT)
            deemInferior.triggeredAbilities.shouldBeEmpty()
        }

        "CR 601.2f: 'costs {1} less for each card you've drawn this turn' counts a tally, not a zone" {
            // A `PerMatching` over a zone would count cards *in a hand*, which is a different number:
            // cards drawn and then discarded still reduce, and cards that were already in hand do not.
            deemInferior.costReduction shouldBe CostReduction.PerDrawThisTurn(1)
        }

        "CR 401.1/108.3: the *owner* puts it into their library, so the whole effect is a clause" {
            deemInferior.ownerLibraryPlacement shouldBe OwnerLibraryPlacement
            deemInferior.declaredClauses shouldContainExactly listOf(OwnerLibraryPlacement)
        }

        // ---------------------------------------------------------------- registration

        "all three cards are registered in the catalog under their printed names" {
            listOf(cleansingWildfire, maskedVandal, deemInferior).forEach { card ->
                MvpCards.definitions[CardRef(card.characteristics.name)] shouldBe card
            }
        }

        "W9-F: the pool's only target-controller search and only owner-library placement" {
            // Whole-registry pins, so a second card of either shape cannot arrive unnoticed — the same
            // argument the changeling and cost-reduction pins make.
            MvpCards.definitions.values
                .filterIsInstance<SpellDefinition>()
                .filter { it.librarySearch?.searcher == LibrarySearchSearcher.TARGET_CONTROLLER }
                .map { it.characteristics.name } shouldContainExactly listOf("Cleansing Wildfire")
            MvpCards.definitions.values
                .filterIsInstance<SpellDefinition>()
                .filter { it.ownerLibraryPlacement != null }
                .map { it.characteristics.name } shouldContainExactly listOf("Deem Inferior")
            MvpCards.definitions.values
                .flatMap { it.triggeredAbilities }
                .count { it.optionalGraveyardExileGate != null } shouldBe 1
        }
    })
