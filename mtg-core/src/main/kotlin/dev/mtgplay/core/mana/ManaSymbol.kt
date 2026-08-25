package dev.mtgplay.core.mana

/**
 * One symbol in a mana cost (CR 107.4), in Scryfall's brace syntax.
 *
 * The five shapes below are exactly the cost shapes the MVP pool exercises (docs/decklists.md):
 * colored {W}…{G}, generic {N}, colorless {C} (a demand for specifically colorless mana,
 * distinct from generic — CR 107.4c), two-color hybrid {G/U}, and Phyrexian {R/P}.
 *
 * **{X} arrived with `FW-X`** (CR 107.3), and it is the one symbol that is not a demand for a
 * fixed quantity of mana: it is a *variable* whose value the caster announces at CR 601.2b, before
 * the total cost is determined. [X] therefore stands for the variable itself, never for a chosen
 * value — a cost carrying it is not payable as it stands, and [ManaCost.substitutingX] is what turns
 * it into the generic mana actually paid. Its [manaValue] is `0`, which is CR 202.3b read literally:
 * "the value of X is treated as zero" everywhere except on the stack, where the announced value is
 * recorded on the cast record rather than on the printed cost.
 *
 * The other shapes outside the pool remain unsupported and [ManaCost.parse] rejects them loudly:
 * snow {S}, monocolored hybrid {2/W}, and hybrid Phyrexian {G/U/P}.
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
     * The variable mana symbol `{X}` (CR 107.3): a placeholder for a value the caster announces as the
     * spell is put on the stack (CR 601.2b), which then becomes part of the spell's total cost as that
     * much **generic** mana. Additive, flagged core (`FW-X`).
     *
     * **This symbol is never paid; it is replaced.** A cost still carrying it has no expansion into
     * payable units — "pay {X}" is not an instruction until X has a value — so the payment machinery
     * refuses it loudly rather than guessing a value, and [ManaCost.substitutingX] is the only way
     * through. That refusal is the structural guarantee behind the announcement: an unannounced X
     * cannot reach a payment plan by any code path.
     *
     * **[manaValue] is 0, and that is CR 202.3b rather than a convenience.** "While a spell is on the
     * stack, the value of X is the value chosen or determined for it. In every other zone, the value of
     * X is treated as zero." The printed cost is a characteristic of the *card*, which is in some other
     * zone whenever anything asks; the announced value belongs to the spell, and the engine records it
     * on the cast record ([dev.mtgplay.core.state.StackEntry.Spell.chosenX]) precisely so this printed
     * symbol never has to lie about it.
     *
     * It contributes **no colour** (CR 202.2): `{X}{R}` is red because of its `{R}`, and Kaervek's
     * Torch would be red with X announced as zero.
     */
    data object X : ManaSymbol {
        // CR 202.3b: zero everywhere but the stack; the stack's value lives on the cast record.
        override val manaValue: Int get() = 0
        override val colors: Set<Color> get() = emptySet()

        override fun render(): String = "{X}"
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
