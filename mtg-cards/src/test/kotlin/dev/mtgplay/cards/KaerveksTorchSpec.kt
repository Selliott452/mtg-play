package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.StackTargetTax
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.ManaCost
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentSetOf

/**
 * Kaervek's Torch against its oracle text (CR 201–208), and the one registry-wide invariant its cost
 * increase depends on.
 */
class KaerveksTorchSpec :
    StringSpec({
        "CR 202/205: a {X}{R} sorcery that deals damage to any target" {
            val printed = kaerveksTorch.characteristics
            printed.manaCost shouldBe ManaCost.parse("{X}{R}")
            printed.cardTypes shouldBe persistentSetOf(CardType.SORCERY)
            printed.powerToughness.shouldBeNull()
            kaerveksTorch.timing shouldBe TimingClass.SORCERY_SPEED
            // CR 115.4: "any target" — a creature, a player, or a planeswalker.
            kaerveksTorch.targetSpec shouldBe TargetSpec.AnyTarget
        }

        "CR 601.2f: it taxes spells that target it by {2} while it is on the stack" {
            kaerveksTorch.stackTargetTax shouldBe StackTargetTax(KAERVEKS_TORCH_TAX)
            KAERVEKS_TORCH_TAX shouldBe 2
        }

        "CR 107.3: it is the pool's first card with {X} in its printed mana cost" {
            // `FW-X` shipped the variable with synthetic fixtures alone because neither gauntlet card
            // printing one could be encoded. Pinned so the claim in the file header stays true or the
            // test that made it stops being the only witness.
            kaerveksTorch.characteristics.manaCost
                ?.hasX shouldBe true
        }

        "CR 601.2b: the pool's counterspells are modal, which is why the tax has to price a mode" {
            // The first draft of `StackTargetTax.kt` refused a modal card that could name a spell, on the
            // grounds that its cheapest cost depends on a mode CR 601.2b has not settled. This assertion
            // is what proved that refusal unshippable: Pyroblast and the Elemental Blasts print "counter
            // target spell, **or** destroy target permanent", and a Torch is exactly the red spell they
            // are held for. Pinned in the positive direction so the reason survives.
            MvpCards.definitions.values
                .filterIsInstance<SpellDefinition>()
                .filter { definition -> definition.modes.any { it.targetSpec is TargetSpec.SpellOnStack } }
                .map { it.characteristics.name }
                .shouldNotBeEmpty()
        }

        "CR 601.2c/f: no multi-line card in the pool targets a spell, which the cost increase relies on" {
            MvpCards.definitions.values
                .filterIsInstance<SpellDefinition>()
                .filter { definition -> definition.additionalTargetSpecs.any { it is TargetSpec.SpellOnStack } }
                .map { it.characteristics.name }
                .shouldBeEmpty()
        }
    })
