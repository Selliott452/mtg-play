package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ManaAbilityCost
import dev.mtgplay.core.definition.ManaAbilityRider
import dev.mtgplay.core.definition.ManaAmount
import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaSymbol
import dev.mtgplay.core.mana.ManaType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * The pool's first creature mana sources (CR 605.1a on a CR 302 permanent): printed boxes against
 * the oracle cards (CR 201–208) and the intrinsic `{T}`: Add `{G}` ability each carries. The
 * CR 302.6 consequence — a summoning-sick one taps for nothing — is a rules behaviour and is
 * tested in `mtg-rules`' `ManaSourceSummoningSicknessSpec`.
 */
class ManaCreaturesSpec :
    StringSpec({
        val elves = mapOf(elvishMystic to "Elvish Mystic", fyndhornElves to "Fyndhorn Elves")

        "CR 201-208: both mana Elves are {G} 1/1 Elf Druids with no printed keyword" {
            elves.forEach { (definition: SpellDefinition, name) ->
                val printed = definition.characteristics
                printed.name shouldBe name
                printed.manaCost shouldBe ManaCost.parse("{G}")
                printed.supertypes.shouldBeEmpty()
                printed.cardTypes shouldBe persistentSetOf(CardType.CREATURE)
                printed.subtypes shouldBe persistentSetOf(Subtype("Elf"), Subtype("Druid"))
                printed.powerToughness shouldBe PrintedPowerToughness(power = 1, toughness = 1)
                printed.keywords.shouldBeEmpty()
            }
        }

        "CR 605.1a: each mana Elf has exactly one intrinsic {T} mana ability adding {G}" {
            elves.keys.forEach { definition ->
                definition.manaAbilities shouldBe
                    persistentListOf(ManaAbility(persistentListOf(ManaType.GREEN)))
            }
        }

        "CR 201-208: Priest of Titania is a {1}{G} 1/1 Elf Druid" {
            val printed = priestOfTitania.characteristics
            printed.name shouldBe "Priest of Titania"
            printed.manaCost shouldBe ManaCost.parse("{1}{G}")
            printed.supertypes.shouldBeEmpty()
            printed.cardTypes shouldBe persistentSetOf(CardType.CREATURE)
            printed.subtypes shouldBe persistentSetOf(Subtype("Elf"), Subtype("Druid"))
            printed.powerToughness shouldBe PrintedPowerToughness(power = 1, toughness = 1)
            printed.keywords.shouldBeEmpty()
        }

        "CR 605.2: Priest of Titania adds one {G} per Elf on the battlefield, not per Elf you control" {
            val ability = priestOfTitania.manaAbilities.single()
            ability.options shouldBe listOf(ManaType.GREEN)
            ability.cost shouldBe persistentListOf(ManaAbilityCost.TapSelf)
            // "for each Elf **on the battlefield**": the opponent's Elves count too, which is the one
            // detail an "each Elf you control" reading would get wrong, and it would get it wrong
            // silently and downward.
            ability.amount shouldBe
                ManaAmount.PerPermanent(PermanentFilter(Subtype("Elf"), controlledByYou = false))
            // It is itself an Elf, so the count is never zero while it is around to be tapped.
            Subtype("Elf") shouldBeIn priestOfTitania.characteristics.subtypes
        }

        "CR 302.1: a mana Elf is a sorcery-speed, untargeted creature spell with no other abilities" {
            elves.keys.forEach { definition ->
                definition.timing shouldBe TimingClass.SORCERY_SPEED
                definition.targetSpec shouldBe TargetSpec.None
                definition.activatedAbilities.shouldBeEmpty()
                definition.triggeredAbilities.shouldBeEmpty()
                definition.staticContinuousEffects.shouldBeEmpty()
            }
        }

        "docs/design/mana-payment.md §2: the two functional reprints are distinct printed cards" {
            // The payment equivalence relation keys on CardRef as well as production profile, so
            // these never merge into one source class — a deliberate safety margin.
            CardRef(elvishMystic.characteristics.name) shouldNotBe CardRef(fyndhornElves.characteristics.name)
        }

        "both mana Elves are in the registry under their printed names (CR 201)" {
            elves.forEach { (definition, name) ->
                MvpCards.definitions[CardRef(name)] shouldBe definition
            }
        }

        // ---- W8-B ------------------------------------------------------------------------------

        "CR 201-208: Elves of Deep Shadow is a {G} 1/1 Elf Druid" {
            val printed = elvesOfDeepShadow.characteristics
            printed.name shouldBe "Elves of Deep Shadow"
            printed.manaCost shouldBe ManaCost.parse("{G}")
            printed.supertypes.shouldBeEmpty()
            printed.cardTypes shouldBe persistentSetOf(CardType.CREATURE)
            printed.subtypes shouldBe persistentSetOf(Subtype("Elf"), Subtype("Druid"))
            printed.powerToughness shouldBe PrintedPowerToughness(power = 1, toughness = 1)
            printed.keywords.shouldBeEmpty()
        }

        "CR 605.1a: Elves of Deep Shadow taps for {B} — a green Elf that makes black" {
            val ability = elvesOfDeepShadow.manaAbilities.single()
            // Not {G}. The colour is the reason the card is in Spy Combo at all, and a green
            // one-drop Elf that taps for green is a different card the deck does not want.
            ability.options shouldBe listOf(ManaType.BLACK)
            ability.cost shouldBe persistentListOf(ManaAbilityCost.TapSelf)
            ability.amount shouldBe ManaAmount.Fixed(1)
        }

        "CR 605.1a: the '1 damage to you' clause is a rider on the mana ability, not a second ability" {
            // The whole encoding decision. It does not target, it could add mana, and it is not a
            // loyalty ability, so CR 605.1a keeps it a mana ability — stackless, unrespondable, and
            // in the payment planner. Demoting it to an activated ability would delete the card.
            elvesOfDeepShadow.manaAbilities.single().rider shouldBe ManaAbilityRider.DamageToController(1)
            elvesOfDeepShadow.activatedAbilities.shouldBeEmpty()
            elvesOfDeepShadow.triggeredAbilities.shouldBeEmpty()
        }

        "CR 201-208: Burning-Tree Emissary is a {R/G}{R/G} 2/2 Human Shaman" {
            val printed = burningTreeEmissary.characteristics
            printed.name shouldBe "Burning-Tree Emissary"
            printed.manaCost shouldBe ManaCost.parse("{R/G}{R/G}")
            printed.supertypes.shouldBeEmpty()
            printed.cardTypes shouldBe persistentSetOf(CardType.CREATURE)
            printed.subtypes shouldBe persistentSetOf(Subtype("Human"), Subtype("Shaman"))
            printed.powerToughness shouldBe PrintedPowerToughness(power = 2, toughness = 2)
            printed.keywords.shouldBeEmpty()
        }

        "CR 107.4: the Emissary's cost is two hybrid symbols, and CR 202.2 makes it both red and green" {
            val cost = burningTreeEmissary.characteristics.manaCost
            cost.shouldNotBeNull()
            cost.symbols shouldBe
                listOf(
                    ManaSymbol.Hybrid(Color.RED, Color.GREEN),
                    ManaSymbol.Hybrid(Color.RED, Color.GREEN),
                )
            // Two hybrids, not "{R}{G}": each half is independently payable either way, which is what
            // lets an Emissary's own {R}{G} cast the next one whichever colours are floating.
            burningTreeEmissary.characteristics.colors shouldBe persistentSetOf(Color.RED, Color.GREEN)
        }

        "CR 106.1: the Emissary's entry trigger adds {R}{G} and is not a mana ability" {
            val trigger = burningTreeEmissary.triggeredAbilities.single()
            trigger.condition shouldBe TriggerCondition.EnteredBattlefieldSelf
            trigger.addsMana shouldBe persistentListOf(ManaType.RED, ManaType.GREEN)
            // CR 605.1b wants a trigger off a *mana ability*; entering the battlefield is not one. So
            // this uses the stack and is respondable — which is why it is not declared here.
            burningTreeEmissary.triggeredManaAbilities.shouldBeEmpty()
            burningTreeEmissary.manaAbilities.shouldBeEmpty()
        }

        "CR 302.1: both W8-B creatures are sorcery-speed, untargeted creature spells" {
            listOf(elvesOfDeepShadow, burningTreeEmissary).forEach { definition ->
                definition.timing shouldBe TimingClass.SORCERY_SPEED
                definition.targetSpec shouldBe TargetSpec.None
                definition.staticContinuousEffects.shouldBeEmpty()
            }
        }

        "both W8-B creatures are in the registry under their printed names (CR 201)" {
            mapOf(
                elvesOfDeepShadow to "Elves of Deep Shadow",
                burningTreeEmissary to "Burning-Tree Emissary",
            ).forEach { (definition, name) ->
                MvpCards.definitions[CardRef(name)] shouldBe definition
            }
        }

        // ---------------------------------------------------------------- Tinder Wall (`W9-F`)

        "CR 201/205: Tinder Wall is a {G} 0/3 Plant Wall with defender" {
            with(tinderWall.characteristics) {
                name shouldBe "Tinder Wall"
                manaCost shouldBe ManaCost.parse("{G}")
                cardTypes shouldBe persistentSetOf(CardType.CREATURE)
                subtypes shouldBe persistentSetOf(Subtype("Plant"), Subtype("Wall"))
                powerToughness shouldBe PrintedPowerToughness(power = 0, toughness = 3)
                // CR 702.3b: printed and honoured — a Wall that never attacks is the only kind that
                // is ever *blocking* anything, which its second ability depends on.
                keywords shouldBe persistentSetOf(Keyword.DEFENDER)
            }
            tinderWall.timing shouldBe TimingClass.SORCERY_SPEED
            tinderWall.targetSpec shouldBe TargetSpec.None
        }

        "CR 605.1a: 'Sacrifice this creature: Add {R}{R}' is a mana ability, not an activated one" {
            val ability = tinderWall.manaAbilities.single()
            // No target, could add mana, not a loyalty ability — so it never uses the stack (CR 605.3a)
            // and stays in the payment planner, even though its cost destroys its own source.
            ability.options shouldContainExactly listOf(ManaType.RED)
            ability.cost shouldContainExactly listOf(ManaAbilityCost.SacrificeSelf)
            ability.amount shouldBe ManaAmount.Fixed(2)
            ability.rider.shouldBeNull()
        }

        "CR 115.1b/509.1: the second ability targets the creature the Wall is blocking" {
            val ability = tinderWall.activatedAbilities.single()
            // CR 602.1: printed order, so the {R} is paid before the source is sacrificed.
            ability.cost shouldContainExactly
                listOf(AbilityCost.Mana(ManaCost.parse("{R}")), AbilityCost.SacrificeSelf)
            // A restriction on the *source*, not on the candidate — which is why it is a TargetSpec
            // member and not a PermanentRestriction, and why the relation is captured at activation
            // (CR 113.7c): by the CR 608.2b re-check the Wall is a new object in a graveyard.
            ability.targetSpec shouldBe TargetSpec.CreatureBlockedBySource
            // CR 602.5a: an ordinary instant-speed activation; nothing on the card restricts the window.
            ability.timing shouldBe TimingClass.INSTANT_SPEED
            ability.oncePerTurn shouldBe false
        }

        "Tinder Wall is in the registry under its printed name (CR 201)" {
            MvpCards.definitions[CardRef("Tinder Wall")] shouldBe tinderWall
        }
    })
