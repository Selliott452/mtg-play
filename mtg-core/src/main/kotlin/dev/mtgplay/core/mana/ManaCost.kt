package dev.mtgplay.core.mana

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList

/**
 * A mana cost: a non-empty, ordered sequence of [ManaSymbol]s in printed order (CR 202.1).
 *
 * [parse] and [render] round-trip Scryfall's brace syntax exactly (`"{1}{G}{G}"`, `"{G/U}"`,
 * `"{R/P}"`). A card with *no* mana cost — a land, e.g. — is modeled as the **absence** of a
 * [ManaCost] (see [dev.mtgplay.core.card.PrintedCharacteristics.manaCost]), never as an empty
 * symbol list; `{0}` is a real cost of one [ManaSymbol.Generic] symbol.
 *
 * Payment logic (which mana can pay which symbol, hybrid choice, Phyrexian life payment) is
 * deliberately out of scope until Phase 2; this type is vocabulary only.
 *
 * @property symbols the cost's symbols in printed order; never empty.
 */
data class ManaCost(
    val symbols: PersistentList<ManaSymbol>,
) {
    init {
        require(symbols.isNotEmpty()) {
            "a mana cost has at least one symbol; a card with no mana cost is modeled as the absence of a ManaCost"
        }
    }

    /** The cost's mana value: the sum of its symbols' contributions (CR 203.3). */
    val manaValue: Int get() = symbols.sumOf(ManaSymbol::manaValue)

    /**
     * The colors this cost gives an object (CR 202.2), in canonical WUBRG order (CR 105.1).
     * Hybrid symbols contribute both their colors, Phyrexian symbols their one color; a cost of
     * only generic and `{C}` symbols contributes none.
     */
    val colors: Set<Color>
        get() = Color.entries.filter { color -> symbols.any { color in it.colors } }.toSet()

    /** Renders this cost in Scryfall brace syntax, e.g. `"{1}{G}{G}"`. */
    fun render(): String = symbols.joinToString(separator = "") { it.render() }

    companion object {
        /**
         * Parses a cost in Scryfall brace syntax, e.g. `"{1}{G}{G}"`, `"{G/U}"`, `"{R/P}"`.
         *
         * Fails loudly (with [IllegalArgumentException]) on malformed text and on any symbol
         * outside the MVP mana model — notably `{X}` (deliberately unsupported, architect
         * decision P1.1), snow `{S}`, and monocolored hybrid `{2/W}` — rather than silently
         * approximating. The empty string is rejected too: a card with no mana cost is the
         * absence of a [ManaCost], not an empty one.
         */
        fun parse(text: String): ManaCost {
            require(text.isNotEmpty()) {
                "empty mana-cost string: a card with no mana cost is modeled as the absence of a ManaCost"
            }
            val symbols = mutableListOf<ManaSymbol>()
            var index = 0
            while (index < text.length) {
                require(text[index] == '{') { "expected '{' at index $index of \"$text\"" }
                val close = text.indexOf('}', startIndex = index)
                require(close != -1) { "unterminated mana symbol in \"$text\"" }
                symbols += parseSymbol(text.substring(index + 1, close), text)
                index = close + 1
            }
            return ManaCost(symbols.toPersistentList())
        }

        private fun parseSymbol(
            token: String,
            cost: String,
        ): ManaSymbol {
            val color = singleColor(token)
            return when {
                color != null -> ManaSymbol.Colored(color)
                token == "C" -> ManaSymbol.Colorless
                token.isNotEmpty() && token.all { it in '0'..'9' } -> ManaSymbol.Generic(token.toInt())
                else -> parseCompound(token, cost)
            }
        }

        private fun parseCompound(
            token: String,
            cost: String,
        ): ManaSymbol {
            val parts = token.split('/')
            if (parts.size == 2) {
                val first = singleColor(parts[0])
                val second = singleColor(parts[1])
                if (first != null && parts[1] == "P") return ManaSymbol.Phyrexian(first)
                if (first != null && second != null) return ManaSymbol.Hybrid(first, second)
            }
            throw IllegalArgumentException(
                "unsupported mana symbol {$token} in \"$cost\": the MVP mana model covers {W}{U}{B}{R}{G}, {N}, " +
                    "{C}, two-color hybrid, and Phyrexian only ({X} is deliberately unsupported — architect " +
                    "decision, P1.1)",
            )
        }

        private fun singleColor(part: String): Color? =
            part.singleOrNull()?.let { letter -> Color.entries.firstOrNull { it.letter == letter } }
    }
}
