package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.EntersTapped
import dev.mtgplay.core.definition.LibraryLook
import dev.mtgplay.core.definition.LibraryLookMode
import dev.mtgplay.core.definition.LibrarySearch
import dev.mtgplay.core.definition.LibrarySearchFilter
import dev.mtgplay.core.definition.ManaAbilityCost
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentSetOf

/**
 * The printed half of the colourless-artifact packet against the oracle cards (CR 201–205): each
 * card's type line and mana cost, the shape of every ability it prints (CR 602.1 composite costs,
 * CR 603.6a–b trigger conditions, the CR 701.18 search filter), and the registry entries.
 *
 * Their *behaviour* — the cards actually drawn, the land actually found — is played end-to-end through
 * the real engine in the acceptance module's `UtilityLandAndArtifactAcceptanceSpec`. Nothing here
 * asserts a game outcome.
 */
class ColorlessArtifactsSpec :
    StringSpec({

        "CR 201 / CR 301: Ichor Wellspring is a {2} artifact with no mana ability and no P/T box" {
            with(ichorWellspring.characteristics) {
                name shouldBe "Ichor Wellspring"
                manaCost?.render() shouldBe "{2}"
                supertypes shouldBe persistentSetOf<Supertype>()
                cardTypes shouldBe persistentSetOf(CardType.ARTIFACT)
                subtypes shouldBe persistentSetOf<Subtype>()
                powerToughness.shouldBeNull()
                keywords shouldBe persistentSetOf<Keyword>()
            }
            ichorWellspring.timing shouldBe TimingClass.SORCERY_SPEED
            ichorWellspring.targetSpec shouldBe TargetSpec.None
            ichorWellspring.manaAbilities.shouldBeEmpty()
            ichorWellspring.activatedAbilities.shouldBeEmpty()
        }

        "CR 603.6a-b: Ichor Wellspring's one printed ability is two conditions — entering and dying" {
            // Two TriggeredAbility entries, because the events are disjoint: one, and only one, can
            // match any given event, so the pair behaves exactly as the printed two-condition ability.
            ichorWellspring.triggeredAbilities.map { it.condition } shouldContainExactly
                listOf(
                    TriggerCondition.EnteredBattlefieldSelf,
                    TriggerCondition.PutIntoGraveyardFromBattlefieldSelf,
                )
            // CR 113.6: both halves function from the battlefield, the second as CR 603.10 last-known
            // information — it is a leaves-the-battlefield trigger, not a graveyard-scoped ability.
            ichorWellspring.triggeredAbilities.map { it.zoneScope } shouldContainExactly
                listOf(TriggerZoneScope.Battlefield, TriggerZoneScope.Battlefield)
            ichorWellspring.triggeredAbilities.map { it.targetSpec } shouldContainExactly
                listOf(TargetSpec.None, TargetSpec.None)
        }

        "CR 701.18: Expedition Map's {2}, {T}, Sacrifice searches for a land card — the widest land filter" {
            with(expeditionMap.characteristics) {
                name shouldBe "Expedition Map"
                manaCost?.render() shouldBe "{1}"
                supertypes shouldBe persistentSetOf<Supertype>()
                cardTypes shouldBe persistentSetOf(CardType.ARTIFACT)
                subtypes shouldBe persistentSetOf<Subtype>()
                powerToughness.shouldBeNull()
            }
            val ability = expeditionMap.activatedAbilities.single()
            ability.cost shouldContainExactly
                listOf(
                    AbilityCost.Mana(ManaCost.parse("{2}")),
                    AbilityCost.TapSelf,
                    AbilityCost.SacrificeSelf,
                )
            // Not BASIC_LAND_CARD (Ash Barrens) and not ISLAND_CARD (Lórien Revealed): the card type alone.
            ability.librarySearch shouldBe LibrarySearch(LibrarySearchFilter.LAND_CARD)
            ability.zoneScope shouldBe AbilityZoneScope.Battlefield
            ability.targetSpec shouldBe TargetSpec.None
        }

        "neither artifact is a mana source — the property that keeps them clear of the {T}-cost payment gap" {
            packetArtifacts.forEach { definition ->
                definition.manaAbilities.shouldBeEmpty()
                definition.triggeredManaAbilities.shouldBeEmpty()
                definition.staticContinuousEffects.shouldBeEmpty()
                // CR 110.5a: neither prints an enters-tapped clause.
                definition.entersTapped shouldBe EntersTapped.Never
            }
        }

        "CR 202: Giant's Boulder is a {1} artifact with no subtype and no P/T box" {
            with(giantsBoulder.characteristics) {
                name shouldBe "Giant's Boulder"
                manaCost?.render() shouldBe "{1}"
                supertypes shouldBe persistentSetOf<Supertype>()
                cardTypes shouldBe persistentSetOf(CardType.ARTIFACT)
                subtypes shouldBe persistentSetOf<Subtype>()
                powerToughness.shouldBeNull()
            }
            giantsBoulder.timing shouldBe TimingClass.SORCERY_SPEED
        }

        "CR 701.17a: Giant's Boulder's enters trigger is scry 2 — a scry, not the surveil it is filed as" {
            val enters = giantsBoulder.triggeredAbilities.single()
            enters.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            // The `FW-CLAUSEHOOK` correction, pinned: LibraryLookMode.Scry, never a graveyard destination.
            enters.libraryLook shouldBe LibraryLook(mode = LibraryLookMode.Scry(GIANTS_BOULDER_SCRY))
            enters.librarySearch.shouldBeNull()
        }

        "CR 605.1a: its mana ability costs {1} and {T} together and offers all five colours" {
            val mana = giantsBoulder.manaAbilities.single()
            mana.cost shouldContainExactly
                listOf(
                    ManaAbilityCost.Mana(ManaCost.parse("{1}")),
                    ManaAbilityCost.TapSelf,
                )
            mana.options shouldContainExactly
                listOf(ManaType.WHITE, ManaType.BLUE, ManaType.BLACK, ManaType.RED, ManaType.GREEN)
            // Not once-per-turn: the {T} is what bounds it (CR 602.5b's alternative).
            mana.oncePerTurn shouldBe false
        }

        "CR 602.1/115.1b: the {7} ability is mana + tap + sacrifice, targeting *any* permanent" {
            val ability = giantsBoulder.activatedAbilities.single()
            ability.cost shouldContainExactly
                listOf(
                    AbilityCost.Mana(ManaCost.parse("{7}")),
                    AbilityCost.TapSelf,
                    AbilityCost.SacrificeSelf,
                )
            // ANY_PERMANENT, not CREATURE: a land or an enchantment is a legal choice. This is the
            // restriction the triage said was missing and that Scour from Existence had already shipped.
            ability.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.ANY_PERMANENT)
            // Instant speed: only Basilisk Gate prints the sorcery restriction (CR 602.5a is the default).
            ability.timing shouldBe TimingClass.INSTANT_SPEED
        }

        "trap T17: Giant's Boulder is a mana source *and* has a {T}-costed ability with a mana component" {
            // The exact shape that used to crash payment enumeration, encoded with no card-side
            // workaround — the reservation lives in `manaSourcesReservedBy` (mana-payment.md §2.2).
            giantsBoulder.manaAbilities.shouldNotBeEmpty()
            val ability = giantsBoulder.activatedAbilities.single()
            ability.cost.contains(AbilityCost.TapSelf) shouldBe true
            ability.cost.any { it is AbilityCost.Mana } shouldBe true
        }

        "all three colourless artifacts are registered under their printed names (CR 201)" {
            val registered: Map<CardRef, CardDefinition> = MvpCards.definitions
            listOf(
                "Ichor Wellspring" to ichorWellspring,
                "Expedition Map" to expeditionMap,
                "Giant's Boulder" to giantsBoulder,
            ).forEach { (name, definition) -> registered[CardRef(name)] shouldBe definition }
        }
    })

/**
 * The two artifacts with **no** mana ability of their own — the property the file header calls out as
 * what kept them clear of trap T17. Giant's Boulder is deliberately not in this list: it is a mana
 * source, and it is here precisely because that trap is now fixed.
 */
private val packetArtifacts: List<CardDefinition> = listOf(ichorWellspring, expeditionMap)
