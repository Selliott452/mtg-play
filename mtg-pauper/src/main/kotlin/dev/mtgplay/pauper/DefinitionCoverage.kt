package dev.mtgplay.pauper

import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.identity.CardRef

/**
 * Checks that a legal deck is also *playable*: every card resolves to a rules [CardDefinition] in a
 * provided registry (P6.1; extended to the sideboard for the thirteen-deck gauntlet).
 *
 * This is distinct from legality (a card can be perfectly legal yet not-yet-encoded): a report's
 * missing list is the exact set of cards the engine cannot yet play because `mtg-cards` has no
 * definition for them. That list is the card-encoding backlog, and a test pins it so every card
 * that lands shows up as a burn-down in the diff.
 *
 * **Mainboard and sideboard are reported separately** ([CoverageReport.main] and
 * [CoverageReport.sideboard]), not merged. The single-game MVP never sideboards, so mainboard
 * coverage alone decides whether a deck can be played today; sideboard coverage is a distinct,
 * later obligation, and conflating the two would make the mainboard number silently regress.
 * [CoverageReport.missing] and [CoverageReport.isPlayable] therefore keep their original mainboard
 * meaning.
 *
 * A [CardRef] without a definition is inert but legal in the engine (it shuffles, draws, and
 * discards) — see `CardDefinition` — so a gap does not stop a game from starting; it only bounds
 * what that game can do.
 */
object DefinitionCoverage {
    /**
     * The coverage of [deck]'s mainboard and sideboard against [definitions] (the registry
     * `mtg-cards` publishes, by default).
     */
    fun check(
        deck: LoadedDeck,
        definitions: Map<CardRef, CardDefinition> = MvpCards.definitions,
    ): CoverageReport =
        CoverageReport(
            deckName = deck.name,
            main = BoardCoverage.of(deck.distinctMainRefs(), definitions),
            sideboard = BoardCoverage.of(deck.distinctSideboardRefs(), definitions),
        )

    /**
     * The coverage of every deck in [decks] against [definitions], plus the union across all of
     * them — the number CI reports for the gauntlet burn-down.
     */
    fun checkAll(
        decks: List<LoadedDeck>,
        definitions: Map<CardRef, CardDefinition> = MvpCards.definitions,
    ): CoverageSummary {
        val perDeck = decks.map { check(it, definitions) }
        return CoverageSummary(
            decks = perDeck,
            main = BoardCoverage.of(decks.flatMap { it.distinctMainRefs() }.distinct(), definitions),
            sideboard = BoardCoverage.of(decks.flatMap { it.distinctSideboardRefs() }.distinct(), definitions),
        )
    }
}

/**
 * The definition coverage of one board (a mainboard or a sideboard).
 *
 * @property distinct every distinct [CardRef] on the board, in first-appearance order.
 * @property missing the subset of [distinct] with no [CardDefinition], in first-appearance order.
 */
data class BoardCoverage(
    val distinct: List<CardRef>,
    val missing: List<CardRef>,
) {
    /** How many distinct cards the board names. */
    val distinctCount: Int get() = distinct.size

    /** How many of those cards `mtg-cards` defines. */
    val encodedCount: Int get() = distinct.size - missing.size

    /** How many of those cards are not yet defined — the backlog. */
    val missingCount: Int get() = missing.size

    /** Whether every card on the board is playable. */
    val isComplete: Boolean get() = missing.isEmpty()

    /** The missing cards' names, sorted — the stable form a test pins and a report prints. */
    val missingNames: List<String> get() = missing.map { it.name }.sorted()

    internal companion object {
        /** The coverage of [distinct] against [definitions]. */
        fun of(
            distinct: List<CardRef>,
            definitions: Map<CardRef, CardDefinition>,
        ): BoardCoverage = BoardCoverage(distinct = distinct, missing = distinct.filter { it !in definitions })
    }
}

/**
 * The outcome of a definition-coverage check for one deck: which of its cards are not yet playable,
 * reported per board.
 *
 * @property deckName the checked deck's name.
 * @property main the mainboard's coverage — what decides whether a game of this deck can be played.
 * @property sideboard the sideboard's coverage; the single-game MVP never sideboards, so this is a
 *   separate, later obligation and never folded into the mainboard number.
 */
data class CoverageReport(
    val deckName: String,
    val main: BoardCoverage,
    val sideboard: BoardCoverage,
) {
    /**
     * The distinct *mainboard* [CardRef]s with no [CardDefinition], in first-appearance order; empty
     * when the mainboard is fully playable. Mainboard-only, as it has always been — the sideboard
     * gap lives in [sideboard].
     */
    val missing: List<CardRef> get() = main.missing

    /** Whether every mainboard card is playable (no missing definitions). */
    val isPlayable: Boolean get() = main.isComplete

    /** The missing *mainboard* cards' names, sorted — the stable form a test pins and a report prints. */
    val missingNames: List<String> get() = main.missingNames
}

/**
 * Coverage across a set of decks plus the union over all of them (the gauntlet burn-down).
 *
 * @property decks the per-deck reports, in the order the decks were supplied.
 * @property main the union of every deck's mainboard: distinct cards across the whole set, and how
 *   many of them are encoded. Deliberately *not* the sum of the per-deck numbers — decks share
 *   cards, and the backlog is a set of cards to write, not a count of decklist slots.
 * @property sideboard the same union over every deck's sideboard.
 */
data class CoverageSummary(
    val decks: List<CoverageReport>,
    val main: BoardCoverage,
    val sideboard: BoardCoverage,
) {
    /** Every distinct card across both boards of every deck that has no definition, sorted by name. */
    val missingNames: List<String> get() = (main.missing + sideboard.missing).distinct().map { it.name }.sorted()

    /** How many distinct cards across both boards of every deck have no definition. */
    val missingCount: Int get() = (main.missing + sideboard.missing).distinct().size

    /**
     * A fixed-width table of the coverage — the form CI prints so the burn-down is legible in a
     * build log.
     */
    fun render(): String {
        val header = listOf("deck", "main enc/dist", "main missing", "side enc/dist", "side missing")
        val rows =
            decks.map { row(it.deckName, it.main, it.sideboard) } +
                listOf(row("TOTAL (distinct)", main, sideboard))
        val table = listOf(header) + rows
        val widths = header.indices.map { column -> table.maxOf { it[column].length } }
        val rule = widths.joinToString(COLUMN_GAP) { "-".repeat(it) }

        fun line(cells: List<String>): String =
            cells.mapIndexed { column, cell -> cell.padEnd(widths[column]) }.joinToString(COLUMN_GAP).trimEnd()

        return (listOf(line(header), rule) + rows.map(::line)).joinToString("\n")
    }

    private fun row(
        label: String,
        mainBoard: BoardCoverage,
        sideBoard: BoardCoverage,
    ): List<String> =
        listOf(
            label,
            "${mainBoard.encodedCount}/${mainBoard.distinctCount}",
            mainBoard.missingCount.toString(),
            "${sideBoard.encodedCount}/${sideBoard.distinctCount}",
            sideBoard.missingCount.toString(),
        )

    private companion object {
        /** The gap between rendered table columns. */
        const val COLUMN_GAP: String = "  "
    }
}
