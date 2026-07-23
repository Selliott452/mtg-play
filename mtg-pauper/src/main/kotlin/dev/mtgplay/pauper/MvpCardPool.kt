package dev.mtgplay.pauper

/**
 * The MVP card pool: the [CardCatalog] parsed from the bundled Scryfall snapshot
 * (`scryfall-mvp.json`), loaded once (P6.1).
 *
 * The snapshot is the architect-staged, offline card-data source (43 cards, CC BY 4.0, its
 * attribution embedded and surfaced via [CardCatalog.attribution]). Every deck-loading and
 * validation entry point that does not take an explicit catalog resolves against this one.
 */
object MvpCardPool {
    /** The absolute classpath path of the staged snapshot. */
    private const val SNAPSHOT_RESOURCE = "/scryfall-mvp.json"

    /** The parsed snapshot, ingested on first access. */
    val catalog: CardCatalog by lazy { ScryfallIngest.parse(readResourceText(SNAPSHOT_RESOURCE)) }
}
