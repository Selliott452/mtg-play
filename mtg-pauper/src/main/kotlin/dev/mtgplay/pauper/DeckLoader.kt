package dev.mtgplay.pauper

import dev.mtgplay.core.identity.CardRef

/**
 * Resolves a [DeckList]'s card names against a [CardCatalog] into a [LoadedDeck] whose every entry
 * carries its [CardMetadata] and [CardRef] (P6.1).
 *
 * Unknown names are reported loudly and *completely*: [load] gathers every unresolved name across
 * both boards and throws [UnknownCardsException] listing all of them, so a mistyped decklist is
 * fixed in one pass rather than one error at a time (CONVENTIONS.md: fail loudly).
 */
class DeckLoader(
    private val catalog: CardCatalog,
) {
    /** Resolves [deckList], throwing [UnknownCardsException] if any card name is not in the catalog. */
    fun load(deckList: DeckList): LoadedDeck {
        val unknown =
            (deckList.main + deckList.sideboard)
                .map { it.cardName }
                .filter { catalog.metadataFor(it) == null }
                .distinct()
        if (unknown.isNotEmpty()) throw UnknownCardsException(unknown)
        return LoadedDeck(
            name = deckList.name,
            main = deckList.main.map(::resolve),
            sideboard = deckList.sideboard.map(::resolve),
        )
    }

    private fun resolve(entry: DeckEntry): ResolvedCard {
        val metadata =
            catalog.metadataFor(entry.cardName)
                ?: error("unreachable: \"${entry.cardName}\" was proven present before resolution")
        return ResolvedCard(count = entry.count, ref = CardRef(entry.cardName), metadata = metadata)
    }
}

/**
 * A decklist whose every entry has been resolved to its catalog [CardMetadata] (P6.1) — the input
 * to [PauperValidator] and [DefinitionCoverage].
 *
 * @property name the deck's display name.
 * @property main the resolved mainboard entries, in list order.
 * @property sideboard the resolved sideboard entries, in list order.
 */
data class LoadedDeck(
    val name: String,
    val main: List<ResolvedCard>,
    val sideboard: List<ResolvedCard>,
) {
    /** The mainboard card total (CR 100.2a). */
    val mainCount: Int get() = main.sumOf { it.count }

    /** The sideboard card total. */
    val sideboardCount: Int get() = sideboard.sumOf { it.count }

    /**
     * The mainboard expanded into one [CardRef] per physical card, in deck order — the list a
     * `MatchConfig` seats as a library (CR 103.1). Only the mainboard enters a game; the sideboard
     * is validated but never played in the single-game MVP.
     */
    fun mainLibrary(): List<CardRef> = main.flatMap { entry -> List(entry.count) { entry.ref } }

    /** The distinct mainboard [CardRef]s, in first-appearance order — the definition-coverage domain. */
    fun distinctMainRefs(): List<CardRef> = main.map { it.ref }.distinct()

    /**
     * The distinct sideboard [CardRef]s, in first-appearance order. Reported separately from
     * [distinctMainRefs] by [DefinitionCoverage]: a card may sit on both boards, and merging the two
     * would hide whether the *mainboard* is playable.
     */
    fun distinctSideboardRefs(): List<CardRef> = sideboard.map { it.ref }.distinct()

    /** The distinct [CardRef]s across both boards, in first-appearance order. */
    fun distinctRefs(): List<CardRef> = (main + sideboard).map { it.ref }.distinct()
}

/**
 * One resolved decklist entry: [count] copies of the catalog card [metadata], addressed by [ref]
 * (P6.1).
 *
 * @property count how many copies (CR 100.2a copy limits apply across both boards).
 * @property ref the printed-name [CardRef] the engine addresses the card by.
 * @property metadata the card's ingested printed facts and legality.
 */
data class ResolvedCard(
    val count: Int,
    val ref: CardRef,
    val metadata: CardMetadata,
)

/**
 * Thrown when a decklist names cards absent from the catalog (P6.1): [names] lists every
 * unresolved name (distinct, in first-appearance order), so the whole set is visible at once.
 *
 * @property names the unresolved card names.
 */
class UnknownCardsException(
    val names: List<String>,
) : IllegalArgumentException("decklist names ${names.size} unknown card(s): $names")
