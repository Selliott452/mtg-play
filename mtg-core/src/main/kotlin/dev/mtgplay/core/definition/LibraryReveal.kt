package dev.mtgplay.core.definition

/**
 * A "reveal the top N cards, put one matching card into your hand, the rest into your graveyard" effect
 * (CR 701.16, CR 707) — Malevolent Rumble's "Reveal the top four cards of your library. You may put a
 * permanent card from among them into your hand. Put the rest into your graveyard." Additive, flagged
 * core (P6.2a). Card-definition *declaration*; `mtg-rules` reveals the cards (public information),
 * surfaces the up-to-one selection among the matching cards, and distributes them.
 *
 * Runs as the last part of a spell's resolution — after the definition's ordinary [ResolutionEffect]
 * (Malevolent Rumble's token creation, an independent clause) — because the selection needs a
 * mid-resolution decision, which the engine orchestrates around the pure effect.
 *
 * @property count how many cards from the top of the library to reveal (Malevolent Rumble's four).
 * @property toHand which revealed cards may be put into the hand (up to one); the rest go to the
 *   graveyard in order.
 */
data class LibraryReveal(
    val count: Int,
    val toHand: RevealedCardFilter,
) {
    init {
        require(count >= 1) { "CR 701.16: a reveal effect reveals at least one card, was $count" }
    }
}

/**
 * Which revealed cards a [LibraryReveal] may put into the hand (CR 707) — the MVP needs only "a permanent
 * card". Sealed as an enum so `mtg-rules` interprets it exhaustively; other predicates are the extension
 * point.
 */
enum class RevealedCardFilter {
    /** A permanent card (CR 110.4a): a card whose types include a permanent type — not an instant or sorcery. */
    PERMANENT_CARD,
}
