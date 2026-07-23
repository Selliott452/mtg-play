package dev.mtgplay.pauper

/**
 * A card's legality in a format, as recorded by Scryfall's `legalities` object (P6.1 ingestion).
 *
 * Scryfall reports one of four values per format; only [LEGAL] permits a card in a deck. The
 * ingestion maps the raw JSON string to this enum, failing loudly on any unrecognised value so a
 * snapshot that grows a new legality kind cannot be silently misread (CONVENTIONS.md: never
 * approximate).
 *
 * @property scryfall the exact lower-case token Scryfall uses in the snapshot.
 */
enum class Legality(
    val scryfall: String,
) {
    /** The card may be played in the format. */
    LEGAL("legal"),

    /** The card is not in the format's card pool at all. */
    NOT_LEGAL("not_legal"),

    /** The card is legal but limited to a single copy (unused by Pauper; modelled for completeness). */
    RESTRICTED("restricted"),

    /** The card is in the pool but forbidden by the banned list. */
    BANNED("banned"),
    ;

    companion object {
        /**
         * The [Legality] for Scryfall's [token], failing loudly on an unrecognised value (P6.1: a
         * malformed snapshot must not be silently misread).
         */
        fun ofScryfall(token: String): Legality =
            entries.firstOrNull { it.scryfall == token }
                ?: error("unrecognised Scryfall legality \"$token\"; expected one of ${entries.map { it.scryfall }}")
    }
}
