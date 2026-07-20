package dev.mtgplay.core.mana

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private data class PoolCost(
    val card: String,
    val cost: String,
    val manaValue: Int,
    val colors: Set<Color>,
)

// Printed mana costs of every distinct nonland card in the two MVP 75s (docs/decklists.md),
// oracle-verified against Scryfall by the architect session on 2026-07-20. The four lands
// (Mountain, Forest, Plains, Ash Barrens) have no mana cost and are covered as
// characteristics-level cases in PrintedCharacteristicsSpec.
private val printedCosts =
    listOf(
        // Mono-Red Madness, main.
        PoolCost("Melded Moxite", "{1}{R}", 2, setOf(Color.RED)),
        PoolCost("Guttersnipe", "{2}{R}", 3, setOf(Color.RED)),
        PoolCost("Sneaky Snacker", "{U}{B}", 2, setOf(Color.BLUE, Color.BLACK)),
        PoolCost("Voldaren Epicure", "{R}", 1, setOf(Color.RED)),
        PoolCost("Fiery Temper", "{1}{R}{R}", 3, setOf(Color.RED)),
        PoolCost("Fireblast", "{4}{R}{R}", 6, setOf(Color.RED)),
        PoolCost("Lava Dart", "{R}", 1, setOf(Color.RED)),
        PoolCost("Lightning Bolt", "{R}", 1, setOf(Color.RED)),
        PoolCost("Grab the Prize", "{1}{R}", 2, setOf(Color.RED)),
        PoolCost("Highway Robbery", "{1}{R}", 2, setOf(Color.RED)),
        PoolCost("Faithless Looting", "{R}", 1, setOf(Color.RED)),
        // Mono-Red Madness, sideboard.
        PoolCost("Relic of Progenitus", "{1}", 1, emptySet()),
        PoolCost("Cast into the Fire", "{1}{R}", 2, setOf(Color.RED)),
        PoolCost("Pyroblast", "{R}", 1, setOf(Color.RED)),
        PoolCost("Cleansing Wildfire", "{1}{R}", 2, setOf(Color.RED)),
        // GW Bogles, main.
        PoolCost("Gladecover Scout", "{G}", 1, setOf(Color.GREEN)),
        PoolCost("Silhana Ledgewalker", "{1}{G}", 2, setOf(Color.GREEN)),
        PoolCost("Slippery Bogle", "{G/U}", 1, setOf(Color.GREEN, Color.BLUE)),
        PoolCost("Abundant Growth", "{G}", 1, setOf(Color.GREEN)),
        PoolCost("Ethereal Armor", "{W}", 1, setOf(Color.WHITE)),
        PoolCost("Rancor", "{G}", 1, setOf(Color.GREEN)),
        PoolCost("Utopia Sprawl", "{G}", 1, setOf(Color.GREEN)),
        PoolCost("Ancestral Mask", "{2}{G}", 3, setOf(Color.GREEN)),
        PoolCost("Armadillo Cloak", "{1}{G}{W}", 3, setOf(Color.WHITE, Color.GREEN)),
        PoolCost("Cartouche of Solidarity", "{W}", 1, setOf(Color.WHITE)),
        PoolCost("Sentinel's Eyes", "{W}", 1, setOf(Color.WHITE)),
        PoolCost("Malevolent Rumble", "{1}{G}", 2, setOf(Color.GREEN)),
        // GW Bogles, sideboard.
        PoolCost("Scattershot Archer", "{G}", 1, setOf(Color.GREEN)),
        PoolCost("Spirit Link", "{W}", 1, setOf(Color.WHITE)),
        PoolCost("Lifelink", "{W}", 1, setOf(Color.WHITE)),
        PoolCost("Ram Through", "{1}{G}", 2, setOf(Color.GREEN)),
        PoolCost("Gut Shot", "{R/P}", 1, setOf(Color.RED)),
        PoolCost("Tamiyo's Safekeeping", "{G}", 1, setOf(Color.GREEN)),
        PoolCost("Flaring Pain", "{1}{R}", 2, setOf(Color.RED)),
    )

// Mana-bearing alternative/additional costs in the pool: the model must parse these too, even
// though payment mechanics arrive in later phases.
private val alternateCosts =
    listOf(
        PoolCost("Fiery Temper, madness cost", "{R}", 1, setOf(Color.RED)),
        PoolCost("Faithless Looting, flashback cost", "{2}{R}", 3, setOf(Color.RED)),
        PoolCost("Sentinel's Eyes, escape cost", "{W}", 1, setOf(Color.WHITE)),
        PoolCost("Ash Barrens, basic landcycling cost", "{1}", 1, emptySet()),
    )

private fun colorLabel(colors: Set<Color>): String =
    if (colors.isEmpty()) "colorless" else colors.joinToString(separator = "") { it.letter.toString() }

/**
 * Every distinct mana cost in the two MVP decklists parses, renders back exactly, and derives
 * the pinned mana value (CR 203.3) and colors (CR 202.2). One test per card, so a failure
 * names the card whose cost shape broke.
 */
class DecklistCostsSpec :
    StringSpec({
        (printedCosts + alternateCosts).forEach { row ->
            val name =
                "${row.card}: ${row.cost} round-trips, mana value ${row.manaValue} (CR 203.3), " +
                    "${colorLabel(row.colors)} (CR 202.2)"
            name {
                val parsed = ManaCost.parse(row.cost)
                parsed.render() shouldBe row.cost
                parsed.manaValue shouldBe row.manaValue
                parsed.colors shouldBe row.colors
            }
        }
    })
