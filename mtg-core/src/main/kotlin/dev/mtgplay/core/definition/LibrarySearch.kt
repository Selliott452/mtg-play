package dev.mtgplay.core.definition

/**
 * A "search your library for a matching card, reveal it, put it into your hand, then shuffle" effect
 * (CR 701.18) — Ash Barrens' basic landcycling "Search your library for a basic land card, reveal it, put it
 * into your hand, then shuffle." Additive, flagged core (P6.2c). Card-definition *declaration*; `mtg-rules`
 * surfaces the up-to-one selection among the matching library cards (searching your own library always
 * permits failing to find, CR 701.18b), reveals the found card (public information, CR 701.18), puts it into
 * the hand, and shuffles the library through the match PRNG (ADR-006 — the shuffle consumes seeded entropy,
 * so replay reproduces the new order).
 *
 * @property toHand which library cards the search may find and put into the hand (Ash Barrens' basic land).
 */
data class LibrarySearch(
    val toHand: LibrarySearchFilter,
)

/**
 * Which cards a [LibrarySearch] may find (CR 701.18) — the pool needs "a basic land card" (Ash Barrens'
 * basic landcycling) and "an Island card" (Lórien Revealed's islandcycling). An enum so `mtg-rules`
 * interprets it exhaustively; other predicates are the extension point.
 */
enum class LibrarySearchFilter {
    /** A basic land card (CR 205.4, CR 305.6): a land card with the basic supertype (Mountain, Forest, Plains). */
    BASIC_LAND_CARD,

    /**
     * An Island card (CR 205.3b, CR 702.28): a card with the Island land type — the basic land Island,
     * and equally any nonbasic land that has the type. Typecycling names a *subtype*, not the basic land
     * (CR 702.28b), so the basic supertype is deliberately not required here.
     */
    ISLAND_CARD,
}
