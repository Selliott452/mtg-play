package dev.mtgplay.core.definition

/**
 * A "reveal the top N cards, put up to M matching cards into your hand, the rest into your graveyard"
 * effect (CR 701.16, CR 707) — Malevolent Rumble's "Reveal the top four cards of your library. You may
 * put a permanent card from among them into your hand. Put the rest into your graveyard.", and
 * Kruphix's Insight's "Reveal the top six cards of your library. Put up to three enchantment cards from
 * among them into your hand and the rest of the revealed cards into your graveyard." Additive, flagged
 * core (P6.2a; [toHandCount] added in P6.3). Card-definition *declaration*; `mtg-rules` reveals the
 * cards (public information), surfaces the up-to-[toHandCount] selection among the matching cards, and
 * distributes them.
 *
 * Runs as the last part of a spell's resolution — after the definition's ordinary [ResolutionEffect]
 * (Malevolent Rumble's token creation, an independent clause) — because the selection needs a
 * mid-resolution decision, which the engine orchestrates around the pure effect.
 *
 * @property count how many cards from the top of the library to reveal (Malevolent Rumble's four,
 *   Kruphix's Insight's six).
 * @property toHand which revealed cards may be put into the hand; the rest go to the graveyard in order.
 * @property toHandCount the **maximum** number of matching cards that may be put into the hand — the
 *   "up to M" of the oracle text (Malevolent Rumble's one, Kruphix's Insight's three). Keeping fewer
 *   (including none) is always legal, since every MVP reveal clause is a "you may"/"up to".
 */
data class LibraryReveal(
    val count: Int,
    val toHand: RevealedCardFilter,
    val toHandCount: Int = 1,
) {
    init {
        require(count >= 1) { "CR 701.16: a reveal effect reveals at least one card, was $count" }
        require(toHandCount in 1..count) {
            "CR 701.16: a reveal effect keeps between 1 and the revealed $count cards, was $toHandCount"
        }
    }
}

/**
 * Which cards from a looked-at or revealed pool may be put into the hand (CR 707) — "a permanent card"
 * (Malevolent Rumble), "an enchantment card" (Kruphix's Insight), "a colorless card" (Ancient Stirrings),
 * "an instant or sorcery card" (Augur of Bolas), "any number of creature cards" (Lead the Stampede).
 * Sealed as an enum so `mtg-rules` interprets it exhaustively; other predicates are the extension point.
 *
 * **Shared between the two clause types on purpose.** It began as [LibraryReveal]'s filter alone and is now
 * also [LibraryLookMode.RevealMatchingToHandRestToBottom]'s, because "which cards qualify" is the same
 * question whether the pool was shown to everyone (CR 701.16a) or only to its controller (CR 701.14a). What
 * differs between the clauses is who saw the pool and where the rest goes — not what counts as a creature
 * card. Keeping one enum is what stops "an instant or sorcery card" from being spelled twice and drifting.
 *
 * Every member is read off **printed** characteristics: the pool lives in a library, and the CR 613 layer
 * system does not reach a library (CR 109.3), so nothing on the battlefield can change what a card in the
 * pool matches.
 */
enum class RevealedCardFilter {
    /** A permanent card (CR 110.4a): a card whose types include a permanent type — not an instant or sorcery. */
    PERMANENT_CARD,

    /** An enchantment card (CR 303.1): a card whose types include enchantment — Auras included. */
    ENCHANTMENT_CARD,

    /**
     * A **colorless** card (CR 105.2c, CR 202.2) — Ancient Stirrings' "a colorless card". Colour is derived
     * from the printed mana cost, so a card with no mana cost at all (every land) is colorless, and so is an
     * artifact or Eldrazi whose cost is all generic. It is deliberately *not* "an artifact or land card":
     * Ancient Stirrings finds a colorless creature, and reading the printed colour is the rule the card
     * states rather than a proxy for it.
     */
    COLORLESS_CARD,

    /** An instant or sorcery card (CR 304.1, CR 307.1) — Augur of Bolas' filter. */
    INSTANT_OR_SORCERY_CARD,

    /** A creature card (CR 302.1) — Lead the Stampede's "any number of creature cards". */
    CREATURE_CARD,
}
