package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.EntersTapped
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * The printed half of the two graveyard-interaction lands, checked against Scryfall oracle text
 * (CR 201–205): Bojuka Bog's three clauses and Haunted Fengraf's two abilities.
 *
 * Engine-driven behaviour — that a *played* Bojuka Bog fires its enters trigger at all (CR 603.6a, the
 * triage's **T18**), that the exile empties a whole graveyard, and that Haunted Fengraf's return is seeded
 * (ADR-006) — lives in the acceptance module's `FilteredLookAndGraveyardAcceptanceSpec`, because none of it
 * is observable from a definition.
 */
class GraveyardHateSpec :
    StringSpec({

        "CR 305 / CR 614.1c: Bojuka Bog is a costless land that enters tapped and adds {B}" {
            with(bojukaBog.characteristics) {
                name shouldBe "Bojuka Bog"
                manaCost.shouldBeNull()
                supertypes shouldBe persistentSetOf<Supertype>()
                cardTypes shouldBe persistentSetOf(CardType.LAND)
                subtypes shouldBe persistentSetOf<Subtype>()
                powerToughness.shouldBeNull()
            }
            bojukaBog.entersTapped shouldBe EntersTapped.Always
            bojukaBog.manaAbilities shouldBe persistentListOf(ManaAbility(persistentListOf(ManaType.BLACK)))
            // A land is played, never cast (CR 305.1), so it is a CardDefinition and declares no timing.
            bojukaBog.activatedAbilities.shouldBeEmpty()
        }

        "CR 115.1a: Bojuka Bog's enters trigger targets a player — either one, including its controller" {
            val trigger = bojukaBog.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            // "Target player's graveyard": the target is the player (CR 115.1a), not a card, and not
            // restricted to an opponent — a Bog may legally exile its own controller's graveyard.
            trigger.targetSpec shouldBe TargetSpec.TargetPlayer
            // The exile is the ability's ordinary effect, not a post-resolution clause.
            trigger.libraryLook.shouldBeNull()
            trigger.libraryReveal.shouldBeNull()
        }

        "CR 602.1: Haunted Fengraf adds {C} and has a {3}, {T}, Sacrifice ability" {
            with(hauntedFengraf.characteristics) {
                name shouldBe "Haunted Fengraf"
                manaCost.shouldBeNull()
                cardTypes shouldBe persistentSetOf(CardType.LAND)
                subtypes shouldBe persistentSetOf<Subtype>()
            }
            // The land enters untapped: no CR 614.1c clause is printed on it.
            hauntedFengraf.entersTapped shouldBe EntersTapped.Never
            hauntedFengraf.manaAbilities shouldBe persistentListOf(ManaAbility(persistentListOf(ManaType.COLORLESS)))

            val ability = hauntedFengraf.activatedAbilities.single()
            // Three components in printed order — the composite shape `FW-MANACOST` unblocked.
            ability.cost shouldBe
                persistentListOf(
                    AbilityCost.Mana(ManaCost.parse("{3}")),
                    AbilityCost.TapSelf,
                    AbilityCost.SacrificeSelf,
                )
            ability.zoneScope shouldBe AbilityZoneScope.Battlefield
            // CR 602.5a: nothing on the card restricts it to sorcery speed.
            ability.timing shouldBe TimingClass.INSTANT_SPEED
            // "A creature card at random from *your* graveyard" targets nothing (CR 115.1).
            ability.targetSpec shouldBe TargetSpec.None
            ability.libraryLook.shouldBeNull()
            ability.librarySearch.shouldBeNull()
        }

        "CR 305: neither land has a triggered ability it does not print" {
            hauntedFengraf.triggeredAbilities.shouldBeEmpty()
            bojukaBog.triggeredAbilities.size shouldBe 1
        }
    })
