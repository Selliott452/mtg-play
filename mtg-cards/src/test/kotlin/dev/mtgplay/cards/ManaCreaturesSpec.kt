package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ManaAbilityCost
import dev.mtgplay.core.definition.ManaAmount
import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeIn
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
    })
