package dev.mtgplay.pauper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * The thirteen-deck gauntlet (Tranche 0): every list loads and is Pauper-legal, and its definition
 * coverage is **pinned**, so each card `mtg-cards` gains shows up as a shrinking number in this
 * file's diff instead of as something a human tracks.
 *
 * Mainboard and sideboard are pinned separately for the reason [DefinitionCoverage] documents: the
 * mainboard number decides whether a deck can be played at all, and merging the sideboard into it
 * would let mainboard progress hide behind sideboard work (or vice versa).
 */
class GauntletCoverageSpec :
    StringSpec({
        val loader = DeckLoader(MvpCardPool.catalog)
        val loaded by lazy { GauntletDecks.all.map(loader::load) }

        "CR 100.2a: all thirteen gauntlet decklists are 60-card mainboards with 15-card sideboards" {
            loaded.map { it.name to (it.mainCount to it.sideboardCount) } shouldBe
                GauntletDecks.all.map { it.name to (MAIN_DECK_SIZE to SIDEBOARD_SIZE) }
        }

        "every gauntlet decklist resolves against the snapshot and passes Pauper validation" {
            loaded.forEach { deck ->
                PauperValidator
                    .validate(deck)
                    .violations
                    .map { it.description }
                    .shouldBeEmpty()
            }
        }

        "the gauntlet definition-coverage burn-down is pinned, mainboard and sideboard separately" {
            val summary = DefinitionCoverage.checkAll(loaded)
            // Printed so the number is visible in a CI log, not only when the pin breaks.
            println(summary.render())

            summary.decks.map { report ->
                Pinned(
                    deckName = report.deckName,
                    mainEncoded = report.main.encodedCount,
                    mainDistinct = report.main.distinctCount,
                    sideboardEncoded = report.sideboard.encodedCount,
                    sideboardDistinct = report.sideboard.distinctCount,
                )
            } shouldBe
                // `W9-A` added Moon-Circuit Hacker, Tolarian Terror, and Clockwork Percussionist: three
                // mainboard cards in three different decks, one row each, and the first two of them the
                // cards that had lost the wave-8 races for their frameworks.
                listOf(
                    // `W9-D` added Monstrous Emergence, an Elves sideboard card: 3 -> 4.
                    Pinned("Elves", 13, 16, 5, 5),
                    Pinned("Gates", 17, 17, 5, 5),
                    Pinned("Grixis Affinity", 22, 22, 7, 7),
                    Pinned("GW Bogles", 18, 18, 8, 8),
                    Pinned("Jeskai Ephemerate", 22, 22, 6, 7),
                    Pinned("Jund Wildfire", 21, 22, 7, 7),
                    // `FW-NINJUTSU` added Ninja of the Deep Hours and Harrier Strix: 6 -> 8.
                    // `W8-E` added Faerie Miscreant: 10 -> 11.
                    // `W9-A` added Moon-Circuit Hacker: 13 -> 14, and the mainboard is now whole.
                    Pinned("Mono Blue Faeries", 14, 14, 6, 6),
                    // `W9-A` added Tolarian Terror on `FW-WARD`: 12 -> 13.
                    Pinned("Mono-Blue Terror", 14, 14, 6, 6),
                    Pinned("Mono-Red Madness", 12, 12, 5, 5),
                    // `W8-E` added Rally at the Hornburg: 8 -> 9.
                    // `W9-A` added Clockwork Percussionist: 11 -> 12.
                    Pinned("Mono Red Rally", 13, 13, 5, 5),
                    // `W8-E` added Troll of Khazad-dûm: 14 -> 15.
                    Pinned("Monster Tron", 20, 21, 6, 6),
                    // `W8-E` added Gatecreeper Vine and Bramble Wurm: 12 -> 14.
                    Pinned("Spy Combo", 20, 21, 7, 8),
                    // `W8-E` added God-Pharaoh's Faithful: 16 -> 17.
                    Pinned("UWX Familiar", 20, 20, 6, 6),
                )
        }

        "the gauntlet totals are pinned: distinct cards, encoded, and missing across all thirteen" {
            val summary = DefinitionCoverage.checkAll(loaded)
            summary.main.distinctCount shouldBe TOTAL_DISTINCT_MAIN
            summary.main.encodedCount shouldBe TOTAL_ENCODED_MAIN
            summary.main.missingCount shouldBe TOTAL_MISSING_MAIN

            summary.sideboard.distinctCount shouldBe TOTAL_DISTINCT_SIDEBOARD
            summary.sideboard.encodedCount shouldBe TOTAL_ENCODED_SIDEBOARD
            summary.sideboard.missingCount shouldBe TOTAL_MISSING_SIDEBOARD

            // Across both boards: the whole card-encoding backlog, deduplicated.
            summary.missingCount shouldBe TOTAL_MISSING_BOTH_BOARDS
            // Cards that appear only in a sideboard and nowhere's mainboard.
            summary.missingCount - summary.main.missingCount shouldBe TOTAL_MISSING_SIDEBOARD_ONLY
        }

        "CR 305.6: no gauntlet deck is blocked on a basic land — all five basics are encoded" {
            val summary = DefinitionCoverage.checkAll(loaded)
            val missing = (summary.main.missingNames + summary.sideboard.missingNames).toSet()
            listOf("Plains", "Island", "Swamp", "Mountain", "Forest").filter { it in missing }.shouldBeEmpty()
        }
    })

/** One pinned per-deck coverage row: encoded and distinct counts for each board. */
private data class Pinned(
    val deckName: String,
    val mainEncoded: Int,
    val mainDistinct: Int,
    val sideboardEncoded: Int,
    val sideboardDistinct: Int,
)

private const val MAIN_DECK_SIZE = 60
private const val SIDEBOARD_SIZE = 15

/** Distinct cards named by at least one gauntlet mainboard. */
private const val TOTAL_DISTINCT_MAIN = 178

/** Of those, how many `mtg-cards` defines — the number this burn-down drives to [TOTAL_DISTINCT_MAIN]. */
private const val TOTAL_ENCODED_MAIN = 174
private const val TOTAL_MISSING_MAIN = 4

/** Distinct cards named by at least one gauntlet sideboard. */
private const val TOTAL_DISTINCT_SIDEBOARD = 48
private const val TOTAL_ENCODED_SIDEBOARD = 46
private const val TOTAL_MISSING_SIDEBOARD = 2

/** The whole backlog: distinct undefined cards across both boards of all thirteen decks. */
private const val TOTAL_MISSING_BOTH_BOARDS = 6

/** Of the backlog, the cards that appear only in sideboards. */
private const val TOTAL_MISSING_SIDEBOARD_ONLY = 2
