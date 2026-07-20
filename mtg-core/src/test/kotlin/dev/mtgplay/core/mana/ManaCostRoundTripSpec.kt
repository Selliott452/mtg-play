package dev.mtgplay.core.mana

import dev.mtgplay.core.random.Rng
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.toPersistentList

private const val SAMPLE_COUNT: Int = 1000
private const val MAX_SYMBOLS: Int = 8
private const val MAX_GENERIC: Int = 20
private const val SYMBOL_SHAPES: Int = 5

private fun nextSymbol(rng: Rng): Pair<ManaSymbol, Rng> {
    val (shape, next) = rng.nextInt(SYMBOL_SHAPES)
    return when (shape) {
        0 -> {
            val (index, after) = next.nextInt(Color.entries.size)
            ManaSymbol.Colored(Color.entries[index]) to after
        }
        1 -> {
            val (amount, after) = next.nextInt(MAX_GENERIC + 1)
            ManaSymbol.Generic(amount) to after
        }
        2 -> ManaSymbol.Colorless to next
        3 -> {
            val (firstIndex, afterFirst) = next.nextInt(Color.entries.size)
            val (offset, afterSecond) = afterFirst.nextInt(Color.entries.size - 1)
            val secondIndex = (firstIndex + 1 + offset) % Color.entries.size
            ManaSymbol.Hybrid(Color.entries[firstIndex], Color.entries[secondIndex]) to afterSecond
        }
        4 -> {
            val (index, after) = next.nextInt(Color.entries.size)
            ManaSymbol.Phyrexian(Color.entries[index]) to after
        }
        else -> error("unreachable symbol shape $shape")
    }
}

private fun nextCost(rng: Rng): Pair<ManaCost, Rng> {
    val (sizeLessOne, afterSize) = rng.nextInt(MAX_SYMBOLS)
    var current = afterSize
    val symbols =
        buildList {
            repeat(sizeLessOne + 1) {
                val (symbol, next) = nextSymbol(current)
                add(symbol)
                current = next
            }
        }
    return ManaCost(symbols.toPersistentList()) to current
}

/**
 * Property tests over generated costs built from every symbol shape in the MVP pool
 * (docs/decklists.md): parse/render round-trips exactly, and derived color sets come back in
 * the canonical order. Generation is driven deterministically by the in-repo PRNG — the
 * seeded generator is the only sanctioned randomness source (ADR-006), so tests draw from it
 * rather than from kotlin.random, which the ForbiddenImport rule bans.
 */
class ManaCostRoundTripSpec :
    StringSpec({
        "parse(render(cost)) == cost across generated MVP-shaped costs" {
            var generator = Rng(0xC057L)
            repeat(SAMPLE_COUNT) {
                val (cost, next) = nextCost(generator)
                generator = next
                ManaCost.parse(cost.render()) shouldBe cost
            }
        }

        "render(parse(text)) == text across generated rendered costs" {
            var generator = Rng(0x7EC7L)
            repeat(SAMPLE_COUNT) {
                val (cost, next) = nextCost(generator)
                generator = next
                val text = cost.render()
                ManaCost.parse(text).render() shouldBe text
            }
        }

        "CR 105.1: derived colors always iterate in canonical WUBRG order" {
            var generator = Rng(0x0C0EL)
            repeat(SAMPLE_COUNT) {
                val (cost, next) = nextCost(generator)
                generator = next
                cost.colors.toList() shouldBe Color.entries.filter { it in cost.colors }
            }
        }
    })
