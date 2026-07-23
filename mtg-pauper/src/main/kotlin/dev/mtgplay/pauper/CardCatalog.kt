package dev.mtgplay.pauper

/**
 * The ingested Scryfall snapshot: every card's [CardMetadata] plus the snapshot's provenance
 * string (P6.1).
 *
 * The catalog is the format layer's card database. Deck loading resolves a printed name to its
 * [CardMetadata] through [byName]; the CLI (P6.4) surfaces [attribution] to honour the snapshot's
 * CC BY 4.0 obligation (ADR-003, README).
 *
 * @property cards every card in the snapshot, in snapshot order.
 * @property attribution the snapshot's provenance/attribution line (the JSON `source` field) —
 *   Scryfall's CC BY 4.0 credit; part of the public API so downstream tools can display it.
 */
class CardCatalog(
    val cards: List<CardMetadata>,
    val attribution: String,
) {
    /** Every card keyed by its exact printed name (CR 201); the decklist-resolution index. */
    val byName: Map<String, CardMetadata> = cards.associateBy { it.name }

    init {
        require(attribution.isNotBlank()) {
            "the snapshot carries no attribution string (CC BY 4.0 obligation, ADR-003)"
        }
        require(cards.size == byName.size) {
            val duplicates =
                cards
                    .groupingBy { it.name }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
            "the snapshot has duplicate card names: $duplicates"
        }
    }

    /** The metadata for [name], or `null` if the snapshot has no such card. */
    fun metadataFor(name: String): CardMetadata? = byName[name]
}
