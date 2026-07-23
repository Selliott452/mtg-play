package dev.mtgplay.pauper

/**
 * Parses the mtg-play decklist text format into a [DeckList] (P6.1).
 *
 * **The format** (documented for authors, packet report):
 * - Lines are UTF-8 text; leading/trailing whitespace on a line is ignored.
 * - A blank line, or a line whose first non-space character is `#`, is a comment and ignored.
 * - `Name: <deck name>` sets the deck's display name (optional; the first one wins).
 * - `Main` (or `Mainboard`) and `Sideboard` headers switch which section following entries land
 *   in; entries before any header default to the mainboard.
 * - Every other line is a card entry `<count> <card name>`: a positive integer, a space, then the
 *   exact printed card name (which may itself contain spaces and apostrophes).
 *
 * Malformed input fails loudly with the offending line number (CONVENTIONS.md: never approximate).
 */
object DeckListParser {
    private const val NAME_PREFIX = "Name:"
    private const val COMMENT_PREFIX = "#"
    private val MAIN_HEADERS = setOf("main", "mainboard")
    private const val SIDEBOARD_HEADER = "sideboard"

    private enum class Section { MAIN, SIDEBOARD }

    /**
     * Parses [text] into a [DeckList]. [fallbackName] names the deck if the text has no `Name:`
     * line.
     */
    fun parse(
        text: String,
        fallbackName: String = "unnamed deck",
    ): DeckList {
        var name: String? = null
        var section = Section.MAIN
        val main = mutableListOf<DeckEntry>()
        val sideboard = mutableListOf<DeckEntry>()

        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            val lineNumber = index + 1
            when {
                line.isEmpty() || line.startsWith(COMMENT_PREFIX) -> Unit
                line.startsWith(NAME_PREFIX) -> if (name == null) name = line.removePrefix(NAME_PREFIX).trim()
                line.lowercase() in MAIN_HEADERS -> section = Section.MAIN
                line.lowercase() == SIDEBOARD_HEADER -> section = Section.SIDEBOARD
                else -> {
                    val entry = parseEntry(line, lineNumber)
                    when (section) {
                        Section.MAIN -> main.add(entry)
                        Section.SIDEBOARD -> sideboard.add(entry)
                    }
                }
            }
        }
        return DeckList(name = name?.takeIf { it.isNotBlank() } ?: fallbackName, main = main, sideboard = sideboard)
    }

    /** Parses one `<count> <name>` entry, failing loudly with [lineNumber] on any malformation. */
    private fun parseEntry(
        line: String,
        lineNumber: Int,
    ): DeckEntry {
        val split = line.indexOfFirst { it.isWhitespace() }
        require(split > 0) { "decklist line $lineNumber is not \"<count> <name>\": \"$line\"" }
        val count =
            line.substring(0, split).toIntOrNull()
                ?: error("decklist line $lineNumber has a non-integer count: \"$line\"")
        require(count > 0) { "decklist line $lineNumber has a non-positive count $count: \"$line\"" }
        val cardName = line.substring(split).trim()
        require(cardName.isNotEmpty()) { "decklist line $lineNumber has a count but no card name: \"$line\"" }
        return DeckEntry(count = count, cardName = cardName)
    }
}
