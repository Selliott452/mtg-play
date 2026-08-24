package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.LibrarySearch
import dev.mtgplay.core.definition.LibrarySearchFilter
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaCost
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
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
                definition.entersTapped shouldBe false
            }
        }

        "both colourless artifacts are registered under their printed names (CR 201)" {
            val registered: Map<CardRef, CardDefinition> = MvpCards.definitions
            listOf(
                "Ichor Wellspring" to ichorWellspring,
                "Expedition Map" to expeditionMap,
            ).forEach { (name, definition) -> registered[CardRef(name)] shouldBe definition }
        }
    })

/** Every artifact this packet's ColorlessArtifacts.kt encodes. */
private val packetArtifacts: List<CardDefinition> = listOf(ichorWellspring, expeditionMap)
