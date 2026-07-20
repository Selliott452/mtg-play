package dev.mtgplay.core.card

import dev.mtgplay.core.mana.ManaCost
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentSetOf

private fun bogle(): PrintedCharacteristics =
    PrintedCharacteristics(
        name = "Slippery Bogle",
        manaCost = ManaCost.parse("{G/U}"),
        supertypes = persistentSetOf(),
        cardTypes = persistentSetOf(CardType.CREATURE),
        subtypes = persistentSetOf(Subtype("Beast")),
        powerToughness = PrintedPowerToughness(1, 1),
    )

private fun mountain(): PrintedCharacteristics =
    PrintedCharacteristics(
        name = "Mountain",
        manaCost = null,
        supertypes = persistentSetOf(Supertype.BASIC),
        cardTypes = persistentSetOf(CardType.LAND),
        subtypes = persistentSetOf(Subtype("Mountain")),
        powerToughness = null,
    )

/**
 * Construction validation of printed characteristics, plus the characteristics-level mana
 * facts for costless cards (CR 202.2, CR 203.3).
 */
class PrintedCharacteristicsSpec :
    StringSpec({
        "CR 208.1: a creature card carries printed power and toughness — Slippery Bogle is 1/1" {
            bogle().powerToughness shouldBe PrintedPowerToughness(1, 1)
        }

        "CR 208.1: a creature card without printed power/toughness is rejected" {
            shouldThrow<IllegalArgumentException> { bogle().copy(powerToughness = null) }
        }

        "CR 208.1: a non-creature card with printed power/toughness is rejected (no Vehicles in the pool)" {
            shouldThrow<IllegalArgumentException> {
                mountain().copy(powerToughness = PrintedPowerToughness(2, 2))
            }
        }

        "CR 300.1: a card with no card type is rejected" {
            shouldThrow<IllegalArgumentException> { mountain().copy(cardTypes = persistentSetOf()) }
        }

        "a blank card name is rejected" {
            shouldThrow<IllegalArgumentException> { mountain().copy(name = " ") }
        }

        "CR 203.3: a card with no mana cost has mana value 0 — Mountain" {
            mountain().manaValue shouldBe 0
        }

        "CR 202.2: a card with no mana cost is colorless — Ash Barrens" {
            val ashBarrens =
                PrintedCharacteristics(
                    name = "Ash Barrens",
                    manaCost = null,
                    supertypes = persistentSetOf(),
                    cardTypes = persistentSetOf(CardType.LAND),
                    subtypes = persistentSetOf(),
                    powerToughness = null,
                )
            ashBarrens.colors shouldBe emptySet()
            ashBarrens.manaValue shouldBe 0
        }

        "CR 205.4: the Basic supertype and CR 205.3 land subtypes are represented — Mountain" {
            (Supertype.BASIC in mountain().supertypes) shouldBe true
            (Subtype("Mountain") in mountain().subtypes) shouldBe true
        }

        "a blank subtype is rejected" {
            shouldThrow<IllegalArgumentException> { Subtype(" ") }
        }
    })
