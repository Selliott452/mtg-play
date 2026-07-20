package dev.mtgplay.core.mana

/**
 * One symbol in a mana cost (CR 107.4), in Scryfall's brace syntax.
 *
 * The five shapes below are exactly the cost shapes the MVP pool exercises (docs/decklists.md):
 * colored {W}…{G}, generic {N}, colorless {C} (a demand for specifically colorless mana,
 * distinct from generic — CR 107.4c), two-color hybrid {G/U}, and Phyrexian {R/P}.
 *
 * **{X} is deliberately unsupported** (architect decision, P1.1): no card in the MVP pool has
 * an {X} cost, so the symbol does not exist here and [ManaCost.parse] rejects it loudly. The
 * same goes for the other shapes outside the pool: snow {S}, monocolored hybrid {2/W}, and
 * hybrid Phyrexian {G/U/P}.
 *
 * Every symbol knows its contribution to mana value (CR 203.3) and to color (CR 202.2).
 * *Payment* semantics — which mana may actually pay a symbol, and Phyrexian life payment — are
 * Phase 2 territory and deliberately absent here.
 */
sealed interface ManaSymbol {
    /** This symbol's contribution to its cost's mana value (CR 203.3). */
    val manaValue: Int

    /** The colors this symbol contributes to the object's colors (CR 202.2). */
    val colors: Set<Color>

    /** Renders this symbol in Scryfall brace syntax, e.g. `{G/U}`. */
    fun render(): String

    /**
     * A single colored mana symbol, e.g. `{G}` (CR 107.4): a demand for one mana of [color].
     */
    data class Colored(
        val color: Color,
    ) : ManaSymbol {
        override val manaValue: Int get() = 1
        override val colors: Set<Color> get() = setOf(color)

        override fun render(): String = "{${color.letter}}"
    }

    /**
     * A generic-mana symbol `{N}` (CR 107.4), payable by [amount] mana of any types; `{0}` is a
     * legal symbol. Contributes [amount] to mana value (CR 203.3) and no color (CR 202.2).
     */
    data class Generic(
        val amount: Int,
    ) : ManaSymbol {
        init {
            require(amount >= 0) { "generic mana amount must be non-negative, was $amount" }
        }

        override val manaValue: Int get() = amount
        override val colors: Set<Color> get() = emptySet()

        override fun render(): String = "{$amount}"
    }

    /**
     * The colorless mana symbol `{C}` (CR 107.4c): a demand for one specifically colorless
     * mana — distinct from generic `{1}`, which any mana can pay. Contributes 1 to mana value
     * (CR 203.3) and no color (CR 202.2).
     */
    data object Colorless : ManaSymbol {
        override val manaValue: Int get() = 1
        override val colors: Set<Color> get() = emptySet()

        override fun render(): String = "{C}"
    }

    /**
     * A two-color hybrid symbol, e.g. `{G/U}` (CR 107.4), payable with either component color.
     * Contributes **both** colors (CR 202.2 — Slippery Bogle is green and blue) and 1 to mana
     * value (CR 203.3, the largest component of a two-color hybrid). [first] and [second]
     * preserve printed order, so `{G/U}` and `{U/G}` are distinct values with distinct
     * renderings.
     */
    data class Hybrid(
        val first: Color,
        val second: Color,
    ) : ManaSymbol {
        init {
            require(first != second) { "hybrid components must be distinct colors, both were $first" }
        }

        override val manaValue: Int get() = 1
        override val colors: Set<Color> get() = setOf(first, second)

        override fun render(): String = "{${first.letter}/${second.letter}}"
    }

    /**
     * A Phyrexian mana symbol, e.g. `{R/P}` (CR 107.4), payable with its color or 2 life
     * (payment lands in Phase 2). Contributes its [color] (CR 202.2 — Gut Shot is red) and 1
     * to mana value (CR 203.3).
     */
    data class Phyrexian(
        val color: Color,
    ) : ManaSymbol {
        override val manaValue: Int get() = 1
        override val colors: Set<Color> get() = setOf(color)

        override fun render(): String = "{${color.letter}/P}"
    }
}
