package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Evasion
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedPowerToughness
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.Color
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * The three real GW-Bogles hexproof one-drops (docs/decklists.md), checked against their oracle
 * printings (CR 201–208): Gladecover Scout `{G}` 1/1 hexproof, Slippery Bogle `{G/U}` 1/1 hexproof
 * (the pool's first hybrid card), and Silhana Ledgewalker `{1}{G}` 1/1 hexproof with the
 * blockable-only-by-flying evasion.
 */
class BoglesCreaturesSpec :
    StringSpec({
        "CR 702.11: Gladecover Scout is a {G} 1/1 Elf Scout with hexproof" {
            with(gladecoverScout.characteristics) {
                name shouldBe "Gladecover Scout"
                manaCost?.render() shouldBe "{G}"
                cardTypes shouldBe setOf(CardType.CREATURE)
                subtypes shouldBe setOf(Subtype("Elf"), Subtype("Scout"))
                powerToughness shouldBe PrintedPowerToughness(1, 1)
                keywords shouldBe setOf(Keyword.HEXPROOF)
                evasions.shouldContainExactly()
            }
            gladecoverScout.timing shouldBe TimingClass.SORCERY_SPEED
            gladecoverScout.targetSpec shouldBe TargetSpec.None
        }

        "CR 107.4: Slippery Bogle is a {G/U} 1/1 Beast with hexproof — the first hybrid-cost card, green and blue" {
            with(slipperyBogle.characteristics) {
                name shouldBe "Slippery Bogle"
                manaCost?.render() shouldBe "{G/U}"
                // CR 202.2: a hybrid symbol contributes both component colors.
                colors shouldBe setOf(Color.GREEN, Color.BLUE)
                manaValue shouldBe 1
                cardTypes shouldBe setOf(CardType.CREATURE)
                subtypes shouldBe setOf(Subtype("Beast"))
                powerToughness shouldBe PrintedPowerToughness(1, 1)
                keywords shouldBe setOf(Keyword.HEXPROOF)
            }
        }

        "CR 509.1b: Silhana Ledgewalker is a {1}{G} 1/1 Elf Rogue with hexproof and blockable-only-by-flying" {
            with(silhanaLedgewalker.characteristics) {
                name shouldBe "Silhana Ledgewalker"
                manaCost?.render() shouldBe "{1}{G}"
                cardTypes shouldBe setOf(CardType.CREATURE)
                subtypes shouldBe setOf(Subtype("Elf"), Subtype("Rogue"))
                powerToughness shouldBe PrintedPowerToughness(1, 1)
                keywords shouldBe setOf(Keyword.HEXPROOF)
                evasions shouldBe setOf(Evasion.BLOCKABLE_ONLY_BY_FLYING)
            }
        }
    })
