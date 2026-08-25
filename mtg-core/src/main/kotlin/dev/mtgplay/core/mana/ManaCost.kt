package dev.mtgplay.core.mana

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
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

    /**
     * Whether this cost carries the variable symbol `{X}` (CR 107.3) — the one question the casting
     * pipeline asks to decide whether a CR 601.2b value announcement is due. Additive (`FW-X`).
     */
    val hasX: Boolean get() = symbols.any { it == ManaSymbol.X }

    /**
     * This cost with every `{X}` replaced by [value] generic mana (CR 107.3, CR 601.2b) — the pure
     * substitution that turns an announced value into a payable cost. Additive (`FW-X`).
     *
     * **A cost with an unannounced `{X}` is deliberately unpayable**, so this is the only bridge
     * between the printed symbol and the payment machinery: the expansion into payable units refuses
     * [ManaSymbol.X] loudly, which is what makes "X reached payment unannounced" unrepresentable
     * rather than merely unlikely.
     *
     * **A zeroed X is dropped from the rendering, unless it is the whole cost.** This is exactly the
     * rule cost *reduction* already applies to a generic symbol it zeroes, and for the same two
     * reasons: `{0}{G}` and `{G}` expand to identical payment units, so keeping the dead symbol would
     * add nothing an agent could observe — while it *would* print in the CLI menu and travel on the
     * wire, splitting one position into two renderings. `{X}` alone with X announced as zero has to
     * survive as `{0}`, because a [ManaCost] is never an empty symbol list.
     *
     * Multiple `{X}` symbols in one cost each take [value] (CR 107.3 — "if a spell has multiple
     * instances of X in its mana cost, they all have the same value"); no card in the pool prints
     * two, and doing the CR-correct thing costs nothing here.
     *
     * @param value the announced value of X; non-negative (CR 601.2b — a chosen value is a number of
     *   mana, and a negative one is a caller defect rather than a rules case).
     */
    fun substitutingX(value: Int): ManaCost {
        require(value >= 0) { "CR 601.2b: an announced value of X is non-negative, was $value" }
        if (!hasX) return this
        val substituted =
            symbols.mapNotNull { symbol ->
                if (symbol == ManaSymbol.X) {
                    // A zeroed X leaves nothing behind, exactly as a zeroed generic does after a
                    // cost reduction; the whole-cost case is rescued below.
                    ManaSymbol.Generic(value).takeIf { value > 0 }
                } else {
                    symbol
                }
            }
        // A ManaCost is never empty: `{X}` alone with X = 0 is the real one-symbol cost `{0}`.
        return if (substituted.isEmpty()) {
            ManaCost(persistentListOf(ManaSymbol.Generic(0)))
        } else {
            ManaCost(substituted.toPersistentList())
        }
    }

    /** Renders this cost in Scryfall brace syntax, e.g. `"{1}{G}{G}"`. */
    fun render(): String = symbols.joinToString(separator = "") { it.render() }

    companion object {
        /**
         * Parses a cost in Scryfall brace syntax, e.g. `"{1}{G}{G}"`, `"{G/U}"`, `"{R/P}"`.
         *
         * `{X}` parses since `FW-X` (CR 107.3) and yields [ManaSymbol.X], the variable itself;
         * see [substitutingX] for how it becomes payable.
         *
         * Fails loudly (with [IllegalArgumentException]) on malformed text and on any symbol
         * outside the mana model — snow `{S}`, monocolored hybrid `{2/W}`, hybrid Phyrexian
         * `{G/U/P}` — rather than silently approximating. The empty string is rejected too: a card
         * with no mana cost is the absence of a [ManaCost], not an empty one.
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
                // CR 107.3: the variable symbol, announced at CR 601.2b and replaced before payment.
                token == "X" -> ManaSymbol.X
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
                "unsupported mana symbol {$token} in \"$cost\": the mana model covers {W}{U}{B}{R}{G}, {N}, " +
                    "{C}, {X}, two-color hybrid, and Phyrexian only",
            )
        }

        private fun singleColor(part: String): Color? =
            part.singleOrNull()?.let { letter -> Color.entries.firstOrNull { it.letter == letter } }
    }
}
