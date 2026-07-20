package dev.mtgplay.core.identity

/**
 * The stable printed-card identity a game object carries across its whole life.
 *
 * Where an [ObjectId] is reborn on every zone change (CR 400.7), a [CardRef] is what stays
 * constant: the printed card that a whole sequence of objects originates from. It is
 * name-based for now (the exact oracle name); Scryfall ids arrive with the ingestion work in
 * Phase 6.
 *
 * @property name the exact printed (oracle) card name; never blank.
 */
@JvmInline
value class CardRef(
    val name: String,
) {
    init {
        require(name.isNotBlank()) { "card name must not be blank" }
    }
}
