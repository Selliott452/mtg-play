package dev.mtgplay.pauper

/**
 * The two MVP fixture decklists, parsed from their bundled resources (P6.1): Mono-Red Madness and
 * GW Bogles (docs/decklists.md). These are the decks the first playable milestone runs.
 */
object MvpDecks {
    private const val MONO_RED_RESOURCE = "/decks/mono-red-madness.deck"
    private const val GW_BOGLES_RESOURCE = "/decks/gw-bogles.deck"

    /** Mono-Red Madness (docs/decklists.md), parsed on first access. */
    val monoRedMadness: DeckList by lazy {
        DeckListParser.parse(readResourceText(MONO_RED_RESOURCE), "Mono-Red Madness")
    }

    /** GW Bogles (docs/decklists.md), parsed on first access. */
    val gwBogles: DeckList by lazy { DeckListParser.parse(readResourceText(GW_BOGLES_RESOURCE), "GW Bogles") }

    /** Both fixture decklists. */
    val all: List<DeckList> get() = listOf(monoRedMadness, gwBogles)
}
