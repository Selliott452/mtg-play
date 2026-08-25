package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ManaAbilityCost
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.Counter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf

/**
 * The printed shape of the three `FW-MANACOST` cards, asserted against their Oracle text.
 *
 * This is the half of correctness the payment oracle structurally cannot supply
 * (docs/design/mana-payment.md §10): `PaymentEnumerationOracle` imports `manaSourceClasses`, so a
 * profile derived correctly from a **wrong declaration** would satisfy it exactly. A cost list that
 * says `{T}` where the card prints no tap symbol, or that omits the once-each-turn restriction, is
 * invisible to every property in `mtg-rules` and visible only here.
 *
 * The Oracle text each assertion pins was fetched from Scryfall's `/cards/collection`, and two of the
 * three disagree with the gauntlet triage; the disagreements are called out on the assertions.
 */
class CostedManaSourcesSpec :
    StringSpec({

        // ---- Saruli Caretaker ----------------------------------------------------------------

        "Oracle: Saruli Caretaker is a {G} 0/3 Dryad with defender" {
            val printed = saruliCaretaker.characteristics
            printed.name shouldBe "Saruli Caretaker"
            printed.manaCost?.render() shouldBe "{G}"
            printed.cardTypes shouldContainExactly setOf(CardType.CREATURE)
            printed.subtypes shouldContainExactly setOf(Subtype("Dryad"))
            printed.powerToughness?.power shouldBe 0
            printed.powerToughness?.toughness shouldBe 3
            Keyword.DEFENDER shouldBe printed.keywords.single()
            saruliCaretaker.timing shouldBe TimingClass.SORCERY_SPEED
            MvpCards.definitions[CardRef("Saruli Caretaker")] shouldBe saruliCaretaker
        }

        "CR 605.1a: Saruli Caretaker's cost is '{T}, Tap an untapped creature you control', in printed order" {
            val ability = saruliCaretaker.manaAbilities.single()
            ability.cost shouldContainExactly
                listOf(ManaAbilityCost.TapSelf, ManaAbilityCost.TapAnotherCreature)
            // "Add one mana of any color" — five options, one mana, no restriction.
            ability.options shouldContainExactly
                listOf(ManaType.WHITE, ManaType.BLUE, ManaType.BLACK, ManaType.RED, ManaType.GREEN)
            ability.oncePerTurn shouldBe false
        }

        // ---- Wall of Roots ---------------------------------------------------------------------

        "Oracle: Wall of Roots is a {1}{G} 0/5 Plant Wall with defender" {
            val printed = wallOfRoots.characteristics
            printed.name shouldBe "Wall of Roots"
            printed.manaCost?.render() shouldBe "{1}{G}"
            printed.cardTypes shouldContainExactly setOf(CardType.CREATURE)
            printed.subtypes shouldContainExactly setOf(Subtype("Plant"), Subtype("Wall"))
            printed.powerToughness?.power shouldBe 0
            printed.powerToughness?.toughness shouldBe 5
            Keyword.DEFENDER shouldBe printed.keywords.single()
            MvpCards.definitions[CardRef("Wall of Roots")] shouldBe wallOfRoots
        }

        "CR 122.1a and CR 602.5b: Wall of Roots costs a -0/-1 counter, has no {T}, and is once each turn" {
            val ability = wallOfRoots.manaAbilities.single()
            // The whole cost: no tap symbol at all, which is why it works while tapped.
            ability.cost shouldContainExactly
                listOf(ManaAbilityCost.PutCounterOnSelf(Counter.MINUS_ZERO_MINUS_ONE))
            // Deliberately -0/-1 and not -1/-1: CR 704.5q annihilates only the latter against +1/+1.
            val counter = ability.cost.single() as ManaAbilityCost.PutCounterOnSelf
            counter.counter shouldBe Counter.PowerToughness(0, -1)
            ability.options shouldContainExactly listOf(ManaType.GREEN)
            ability.oncePerTurn shouldBe true
        }

        // ---- Barrels of Blasting Jelly ---------------------------------------------------------

        "Oracle: Barrels of Blasting Jelly is a {1} artifact with no subtypes" {
            val printed = barrelsOfBlastingJelly.characteristics
            printed.name shouldBe "Barrels of Blasting Jelly"
            printed.manaCost?.render() shouldBe "{1}"
            printed.cardTypes shouldContainExactly setOf(CardType.ARTIFACT)
            printed.powerToughness.shouldBeNull()
            MvpCards.definitions[CardRef("Barrels of Blasting Jelly")] shouldBe barrelsOfBlastingJelly
        }

        "CR 602.5b: Barrels of Blasting Jelly's mana ability is '{1}:' with no tap symbol" {
            // **The triage was wrong here.** It files the ability under the "{N}, {T}" shape shared
            // with Conduit Pylons and Giant's Boulder; the card prints "{1}: Add one mana of any
            // color. Activate only once each turn." — a bare mana cost. Encoding a {T} would make a
            // tapped Barrels stop filtering, and would make the once-each-turn clause redundant.
            val ability = barrelsOfBlastingJelly.manaAbilities.single()
            ability.cost shouldContainExactly listOf(ManaAbilityCost.Mana(ManaCost.parse("{1}")))
            (ManaAbilityCost.TapSelf in ability.cost) shouldBe false
            ability.oncePerTurn shouldBe true
            ability.options shouldContainExactly
                listOf(ManaType.WHITE, ManaType.BLUE, ManaType.BLACK, ManaType.RED, ManaType.GREEN)
        }

        "CR 602.1: Barrels' second ability is '{5}, {T}, Sacrifice this artifact: 5 damage to target creature'" {
            val ability = barrelsOfBlastingJelly.activatedAbilities.single()
            ability.cost shouldContainExactly
                persistentListOf(
                    AbilityCost.Mana(ManaCost.parse("{5}")),
                    AbilityCost.TapSelf,
                    AbilityCost.SacrificeSelf,
                )
            ability.targetSpec shouldBe TargetSpec.TargetPermanent(PermanentRestriction.CREATURE)
            // CR 602.5a's default: no printed timing restriction, so it is instant speed.
            ability.timing shouldBe TimingClass.INSTANT_SPEED
        }
    })
