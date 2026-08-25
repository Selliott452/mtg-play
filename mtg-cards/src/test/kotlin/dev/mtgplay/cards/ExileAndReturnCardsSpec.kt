package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.definition.EachOpponentDiscards
import dev.mtgplay.core.definition.HandRevealChoice
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.RevealedCardOutcome
import dev.mtgplay.core.definition.RevealedCardRestriction
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetCount
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.identity.CardRef
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.collections.immutable.persistentSetOf

/**
 * The five exile-and-return cards against their Scryfall oracle text: printed characteristics
 * (CR 201–208) and the declared shape each printed clause maps onto — the CR 702.88 rebound flag, the
 * CR 603.6c leaves-the-battlefield condition, the CR 701.16a hand-reveal clause and the CR 701.7a
 * each-opponent discard.
 *
 * The *behaviour* of all four frameworks is `mtg-rules`' `ExileAndReturnSpec`; this suite pins the data,
 * including the one distinction that would otherwise be a plausible-looking wrong card: Journey to
 * Nowhere's second ability watches **every** departure (CR 603.6c), not only a departure to a graveyard
 * (CR 603.6b), so exiling the Journey in response still gives the creature back.
 */
class ExileAndReturnCardsSpec :
    StringSpec({

        "CR 202: the printed costs and type lines match the oracle cards" {
            data class Expected(
                val definition: SpellDefinition,
                val name: String,
                val cost: String,
                val types: Set<CardType>,
                val subtypes: Set<String>,
                val powerToughness: PrintedPowerToughness?,
            )
            listOf(
                Expected(ephemerate, "Ephemerate", "{W}", setOf(CardType.INSTANT), emptySet(), null),
                Expected(
                    journeyToNowhere,
                    "Journey to Nowhere",
                    "{1}{W}",
                    setOf(CardType.ENCHANTMENT),
                    emptySet(),
                    null,
                ),
                Expected(
                    mesmericFiend,
                    "Mesmeric Fiend",
                    "{1}{B}",
                    setOf(CardType.CREATURE),
                    setOf("Nightmare", "Horror"),
                    PrintedPowerToughness(1, 1),
                ),
                Expected(duress, "Duress", "{B}", setOf(CardType.SORCERY), emptySet(), null),
                Expected(
                    refurbishedFamiliar,
                    "Refurbished Familiar",
                    "{3}{B}",
                    setOf(CardType.ARTIFACT, CardType.CREATURE),
                    setOf("Zombie", "Rat"),
                    PrintedPowerToughness(2, 1),
                ),
            ).forEach { expected ->
                val printed = expected.definition.characteristics
                printed.name shouldBe expected.name
                printed.manaCost.shouldNotBeNull().render() shouldBe expected.cost
                printed.cardTypes.toSet() shouldBe expected.types
                printed.subtypes.map { it.value }.toSet() shouldBe expected.subtypes
                printed.powerToughness shouldBe expected.powerToughness
            }
        }

        "CR 702.88a: Ephemerate is the one card in the pool that prints rebound" {
            ephemerate.rebound shouldBe true
            ephemerate.timing shouldBe TimingClass.INSTANT_SPEED
            // The keyword is a leave-the-stack replacement, not an ability, so nothing else is declared.
            ephemerate.triggeredAbilities.shouldHaveSize(0)
            listOf(journeyToNowhere, mesmericFiend, duress, refurbishedFamiliar).forEach {
                it.rebound shouldBe false
            }
        }

        "CR 109.5: Ephemerate targets a creature you control, the pool's first decider-relative restriction" {
            // "Target creature you control" offers each seat a different option list, which is what makes
            // this restriction different in kind from the plain CREATURE the removal spells use.
            ephemerate.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.CREATURE_YOU_CONTROL)
        }

        "CR 603.6c: Journey to Nowhere's second ability watches every departure, not only one to a graveyard" {
            journeyToNowhere.triggeredAbilities shouldHaveSize 2
            val enters = journeyToNowhere.triggeredAbilities[0]
            val leaves = journeyToNowhere.triggeredAbilities[1]

            enters.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            enters.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
            // The card's key correctness claim, asserted both ways: CR 603.6b would leave the creature
            // exiled forever when the Journey itself is exiled in response.
            leaves.condition shouldBe TriggerCondition.LeftBattlefieldSelf
            leaves.condition shouldNotBe TriggerCondition.PutIntoGraveyardFromBattlefieldSelf
            leaves.targetSpec shouldBe TargetSpec.None
        }

        "CR 701.16a + CR 607.2: Mesmeric Fiend reveals, exiles the chosen card, and links the return" {
            mesmericFiend.triggeredAbilities shouldHaveSize 2
            val enters = mesmericFiend.triggeredAbilities[0]
            val leaves = mesmericFiend.triggeredAbilities[1]

            enters.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            // "Target opponent reveals their hand and **you** choose": the reveal is targeted, the choice
            // is the Fiend controller's, and the chosen card is exiled as CR 607.2 linked information.
            enters.targetSpec shouldBe TargetSpec.TargetOpponent
            enters.handRevealChoice shouldBe
                HandRevealChoice(
                    restriction = RevealedCardRestriction.NONLAND,
                    outcome = RevealedCardOutcome.EXILE_LINKED,
                )
            leaves.condition shouldBe TriggerCondition.LeftBattlefieldSelf
            leaves.condition shouldNotBe TriggerCondition.PutIntoGraveyardFromBattlefieldSelf
        }

        "CR 701.16a: Duress is the same clause on a spell, narrower and ending in a discard" {
            duress.targetSpec shouldBe TargetSpec.TargetOpponent
            duress.timing shouldBe TimingClass.SORCERY_SPEED
            duress.handRevealChoice shouldBe
                HandRevealChoice(
                    restriction = RevealedCardRestriction.NONCREATURE_NONLAND,
                    outcome = RevealedCardOutcome.DISCARD,
                )
            // The whole card is the clause; it declares no ability and no other clause.
            duress.triggeredAbilities.shouldHaveSize(0)
            duress.eachOpponentDiscards.shouldBeNull()
        }

        "CR 701.7a: Refurbished Familiar's enters trigger is an each-opponent discard with a draw fallback" {
            refurbishedFamiliar.triggeredAbilities shouldHaveSize 1
            val enters = refurbishedFamiliar.triggeredAbilities.single()

            enters.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            enters.eachOpponentDiscards shouldBe
                EachOpponentDiscards(count = 1, drawPerOpponentWhoCannot = 1)
            // The opposite of Duress and Mesmeric Fiend: nothing is revealed, because the deciding seat is
            // the opponent and it is choosing out of its own hidden hand.
            enters.handRevealChoice.shouldBeNull()
        }

        "CR 702.9 + CR 702.41a: Refurbished Familiar prints flying, and reuses the shared affinity" {
            refurbishedFamiliar.characteristics.keywords.toSet() shouldBe setOf(Keyword.FLYING)
            // Affinity for artifacts predates this packet (`FW-COST`); the card declares the shared value
            // rather than a second, drifting copy of it.
            refurbishedFamiliar.costReduction shouldBe affinityForArtifacts
        }

        "CR 202/304: Ghostly Flicker is a plain blue instant costing {2}{U}" {
            with(ghostlyFlicker.characteristics) {
                name shouldBe "Ghostly Flicker"
                manaCost?.render() shouldBe "{2}{U}"
                cardTypes shouldBe persistentSetOf(CardType.INSTANT)
                powerToughness.shouldBeNull()
            }
            ghostlyFlicker.timing shouldBe TimingClass.INSTANT_SPEED
            // Not rebound: only Ephemerate prints it (CR 702.88).
            ghostlyFlicker.rebound shouldBe false
        }

        "CR 601.2c: Ghostly Flicker demands *exactly* two targets, which is a castability rule" {
            val spec = ghostlyFlicker.targetSpec
            spec shouldBe
                TargetSpec.TargetPermanent(
                    PermanentRestriction.ARTIFACT_CREATURE_OR_LAND_YOU_CONTROL,
                    TargetCount.Exactly(2),
                )
            // The minimum is what makes it uncastable with one legal permanent — the first card in the
            // pool for which a minimum above zero decides anything.
            spec.count.minimum shouldBe 2
            spec.count.maximum shouldBe 2
        }

        "CR 115.1b: Ghostly Flicker's restriction is narrower than 'permanent you control'" {
            // The distinction is observable: an Ephemerate deck's own Journey to Nowhere is a permanent
            // it controls and is *not* a legal Ghostly Flicker target, so the blink cannot re-fire it.
            ghostlyFlicker.targetSpec shouldNotBe
                TargetSpec.TargetPermanent(
                    PermanentRestriction.PERMANENT_YOU_CONTROL,
                    TargetCount.Exactly(2),
                )
        }

        "the six exile-and-return cards are registered in the catalog" {
            listOf(
                ephemerate,
                journeyToNowhere,
                mesmericFiend,
                duress,
                refurbishedFamiliar,
                ghostlyFlicker,
            ).forEach { card ->
                MvpCards.definitions[CardRef(card.characteristics.name)] shouldBe card
            }
        }
    })
