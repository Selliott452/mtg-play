package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.OptionalManaThenDraw
import dev.mtgplay.core.definition.TargetPlayerExilesFromGraveyard
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
 * The printed half of the two colourless graveyard artifacts, read line by line off the Scryfall oracle
 * text (CR 201–205): Nihil Spellbomb's `{T}`-sacrifice exile and its optional pay-then-draw dies
 * trigger, and Relic of Progenitus' two abilities.
 *
 * The engine-driven halves — that cracking a Spellbomb puts its dies trigger *above* its own exile
 * ability, that a decline draws nothing, and that Relic's first ability asks the **targeted** player —
 * live in the rules module, because none of it is observable from a definition.
 */
class GraveyardArtifactsSpec :
    StringSpec({

        "CR 301.1: Nihil Spellbomb is a {1} artifact cast at sorcery speed, targeting nothing" {
            with(nihilSpellbomb.characteristics) {
                name shouldBe "Nihil Spellbomb"
                manaCost shouldBe ManaCost.parse("{1}")
                cardTypes shouldBe persistentSetOf(CardType.ARTIFACT)
                subtypes shouldBe persistentSetOf<Subtype>()
                powerToughness.shouldBeNull()
            }
            nihilSpellbomb.timing shouldBe TimingClass.SORCERY_SPEED
            // The *card* targets nothing; its activated ability is what targets a player.
            nihilSpellbomb.targetSpec shouldBe TargetSpec.None
            nihilSpellbomb.manaAbilities.shouldBeEmpty()
        }

        "CR 602.1 / CR 115.1a: Nihil Spellbomb's ability costs {T} + sacrifice and targets a player" {
            val ability = nihilSpellbomb.activatedAbilities.single()
            ability.cost shouldBe persistentListOf(AbilityCost.TapSelf, AbilityCost.SacrificeSelf)
            // "Target player's graveyard" — the player is the target (CR 115.1a), either seat, exactly as
            // Bojuka Bog's trigger reads. Pointing it at one's own graveyard is legal.
            ability.targetSpec shouldBe TargetSpec.TargetPlayer()
            ability.zoneScope shouldBe AbilityZoneScope.Battlefield
            // CR 602.5a: no "activate only as a sorcery" is printed, so it is an instant-speed crack.
            ability.timing shouldBe TimingClass.INSTANT_SPEED
        }

        "CR 601.3b: Nihil Spellbomb's dies trigger is an optional {B} payment, then one card" {
            val trigger = nihilSpellbomb.triggeredAbilities.single()
            // "When this artifact is put into a graveyard from the battlefield" — the zone change, not
            // the cause, so it fires whether the Spellbomb was cracked, destroyed, or sacrificed.
            trigger.condition shouldBe TriggerCondition.PutIntoGraveyardFromBattlefieldSelf
            trigger.optionalManaThenDraw shouldBe
                OptionalManaThenDraw(cost = ManaCost.parse("{B}"), drawCount = NIHIL_SPELLBOMB_DRAW)
            NIHIL_SPELLBOMB_DRAW shouldBe 1
            // It is a "you may", not a cost-then-draw over an object: no discard, no land sacrifice.
            trigger.optionalCostThenDraw.shouldBeNull()
            trigger.optionalDiscardDraw.shouldBeNull()
            trigger.interveningIf.shouldBeNull()
        }

        "CR 301.1: Relic of Progenitus is a {1} artifact cast at sorcery speed" {
            with(relicOfProgenitus.characteristics) {
                name shouldBe "Relic of Progenitus"
                manaCost shouldBe ManaCost.parse("{1}")
                cardTypes shouldBe persistentSetOf(CardType.ARTIFACT)
                subtypes shouldBe persistentSetOf<Subtype>()
            }
            relicOfProgenitus.timing shouldBe TimingClass.SORCERY_SPEED
            relicOfProgenitus.targetSpec shouldBe TargetSpec.None
            relicOfProgenitus.triggeredAbilities.shouldBeEmpty()
        }

        "CR 701.3a: Relic's first ability is a bare {T} that makes the *targeted player* exile" {
            val tapAbility = relicOfProgenitus.activatedAbilities.first()
            tapAbility.cost shouldBe persistentListOf(AbilityCost.TapSelf)
            tapAbility.targetSpec shouldBe TargetSpec.TargetPlayer()
            // The clause is what carries the decider: "target player exiles" makes that player perform
            // the action and therefore choose the card. It is not a graveyard-card target — encoding it
            // as one would put the choice on the controller and make the ability fizzle on an empty yard.
            tapAbility.targetPlayerExilesFromGraveyard shouldBe TargetPlayerExilesFromGraveyard
        }

        "CR 701.3a: Relic's second ability costs {1} and exiles *itself*, never sacrifices it" {
            val exileAbility = relicOfProgenitus.activatedAbilities[1]
            exileAbility.cost shouldBe
                persistentListOf(AbilityCost.Mana(ManaCost.parse("{1}")), AbilityCost.ExileSelf)
            // Where the Relic goes is load-bearing on this very card: its own effect empties every
            // graveyard, so a sacrificed Relic would be putting itself into a zone it is about to clear.
            exileAbility.cost.contains(AbilityCost.SacrificeSelf) shouldBe false
            exileAbility.targetSpec shouldBe TargetSpec.None
            exileAbility.targetPlayerExilesFromGraveyard.shouldBeNull()
            RELIC_OF_PROGENITUS_DRAW shouldBe 1
        }

        "CR 602.1: Relic has exactly the two printed abilities, and only the first taps it" {
            relicOfProgenitus.activatedAbilities.size shouldBe 2
            relicOfProgenitus.activatedAbilities.count { AbilityCost.TapSelf in it.cost } shouldBe 1
        }
    })
