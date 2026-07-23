package dev.mtgplay.pauper

/**
 * A parsed decklist (CR 100.1): the pre-game deck as a name plus counted card entries, split into
 * mainboard and sideboard (P6.1). Names only — resolution to metadata and legality is the
 * [DeckLoader]'s job.
 *
 * @property name the deck's display name.
 * @property main the mainboard entries, in list order (CR 100.2a: at least 60 cards).
 * @property sideboard the sideboard entries, in list order (at most 15 cards; the MVP plays no
 *   sideboarding, but the list is validated).
 */
data class DeckList(
    val name: String,
    val main: List<DeckEntry>,
    val sideboard: List<DeckEntry>,
) {
    /** The mainboard card total (CR 100.2a). */
    val mainCount: Int get() = main.sumOf { it.count }

    /** The sideboard card total. */
    val sideboardCount: Int get() = sideboard.sumOf { it.count }
}

/**
 * One counted line of a decklist: [count] copies of the card named [cardName] (P6.1).
 *
 * @property count how many copies; strictly positive.
 * @property cardName the exact printed card name to resolve against the catalog.
 */
data class DeckEntry(
    val count: Int,
    val cardName: String,
) {
    init {
        require(count > 0) { "a decklist entry has a non-positive count $count for \"$cardName\"" }
        require(cardName.isNotBlank()) { "a decklist entry has a blank card name" }
    }
}
