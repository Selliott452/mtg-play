package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.SpellCostReduction
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentSetOf

/**
 * Sunscape Familiar's printed line (CR 201–208, CR 604.5, CR 601.2f) — the pool's first **other-object**
 * cost reducer, and the card `FW-COST`'s C6 half was built for.
 *
 * The arithmetic, the CR 601.2f lock-in and the CR 118.7a generic-only floor are `mtg-rules`' and are
 * pinned by `CostModificationSpec` against fixtures. What is card-shaped, and therefore tested here, is
 * the *declaration*: which colours it reduces, by how much, and that Defender is really printed on it.
 */
class CostReductionSpec :
    StringSpec({

        "CR 201-208: Sunscape Familiar is a {1}{W} 0/3 Wall with defender" {
            val printed = sunscapeFamiliar.characteristics
            printed.name shouldBe "Sunscape Familiar"
            printed.manaCost shouldBe ManaCost.parse("{1}{W}")
            printed.supertypes.shouldBeEmpty()
            printed.cardTypes shouldBe persistentSetOf(CardType.CREATURE)
            printed.subtypes shouldBe persistentSetOf(Subtype("Wall"))
            printed.powerToughness shouldBe PrintedPowerToughness(power = 0, toughness = 3)
            // CR 702.3b, and not decoration: without the keyword this 0/3 would be offered as an
            // attacker, which is exactly why `FW-COST` shipped the framework and left the card out.
            printed.keywords shouldBe persistentSetOf(Keyword.DEFENDER)
        }

        "CR 604.5: it reduces green and blue spells by {1}, declared on the permanent" {
            sunscapeFamiliar.spellCostReductions shouldBe
                listOf(SpellCostReduction(amount = 1, spellColors = persistentSetOf(Color.BLUE, Color.GREEN)))
        }

        "CR 202.2: 'green spells and blue spells' is a disjunction, and the Familiar's own colour is not in it" {
            val colors = sunscapeFamiliar.spellCostReductions.single().spellColors
            // Either colour qualifies on its own — the engine tests "any of", not "all of".
            colors shouldContain Color.GREEN
            colors shouldContain Color.BLUE
            // And the reducer shares no colour with what it reduces: a white Familiar helping green
            // and blue. Reading the reduction off the reducer's own colour would make it do nothing.
            sunscapeFamiliar.characteristics.colors shouldBe persistentSetOf(Color.WHITE)
            colors shouldNotContain Color.WHITE
        }

        "CR 302.1: it is a sorcery-speed untargeted creature spell whose only text is the static ability" {
            sunscapeFamiliar.timing shouldBe TimingClass.SORCERY_SPEED
            sunscapeFamiliar.targetSpec shouldBe TargetSpec.None
            // The reduction is a rules-modifying continuous effect (CR 613.11), applied at cost
            // determination and never entering the CR 613 layer system — so there is nothing here.
            sunscapeFamiliar.staticContinuousEffects.shouldBeEmpty()
            sunscapeFamiliar.triggeredAbilities.shouldBeEmpty()
            sunscapeFamiliar.activatedAbilities.shouldBeEmpty()
            sunscapeFamiliar.manaAbilities.shouldBeEmpty()
            // The *other-object* slot, not the self slot: the reader is the permanent and the subject
            // is somebody else's spell.
            sunscapeFamiliar.costReduction shouldBe null
        }

        "it is in the registry under its printed name (CR 201)" {
            MvpCards.definitions[CardRef("Sunscape Familiar")] shouldBe sunscapeFamiliar
        }
    })
