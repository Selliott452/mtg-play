package dev.mtgplay.core.definition

import dev.mtgplay.core.card.Keyword
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf

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
 *   (including none) is always legal for a "you may"/"up to" line; see [mandatory].
 * @property disposition where the chosen cards go and what becomes of the rest (CR 701.16) — the axis
 *   that separates the hand-and-graveyard family from Throne of the Dead Three. Additive, flagged core
 *   (`W11`); [RevealDisposition.CHOSEN_TO_HAND_REST_TO_GRAVEYARD] for every client before it.
 * @property mandatory whether the printed line says "**Put** a … card" rather than "you **may** put"
 *   (CR 701.16) — Throne of the Dead Three's *"Put a creature card from among them onto the
 *   battlefield"*. Additive, flagged core (`W11`); `false` for every "up to" line, which is every
 *   client before it.
 *
 *   **The engine stops offering "keep none" when this is set, and that is the whole of it** (ADR-005).
 *   A mandatory instruction with a matching card revealed has no legal way to decline, so offering the
 *   decline would be an illegal line the engine enumerated — the failure ADR-005 names first. It does
 *   not make the *pause* mandatory: with no matching card among the revealed ones nothing is chosen,
 *   which is CR 608.2's "do as much as you can" and not a violation of the instruction.
 * @property entersWithCounters the CR 614.1c counters a chosen card **enters the battlefield with**,
 *   or `null` — Throne of the Dead Three's *"with three `+1/+1` counters on it"*. Additive, flagged
 *   core (`W11`); meaningful only with a battlefield [disposition].
 *
 *   **The effect's replacement, not the permanent's own, and both apply.** [CardDefinition.entersWithCounters]
 *   is a self-replacement the card prints about itself; this is one the *moving effect* creates
 *   (CR 614.1c covers both), so a card with its own clause put onto the battlefield this way gets both
 *   sets. Reusing the same type rather than inventing a second is what keeps "enters with counters"
 *   one concept — and it gives [CounterAmount.Fixed], which the gauntlet's self-replacements never
 *   print, its first real client.
 * @property grantedUntilYourNextTurn keywords a chosen card **gains until its controller's next turn**
 *   once it is on the battlefield (CR 611.2, CR 613.1f) — Throne of the Dead Three's *"It gains
 *   hexproof until your next turn."* Additive, flagged core (`W11`); empty for every other client, and
 *   meaningful only with a battlefield [disposition].
 *
 *   Part of the reveal clause rather than a separate [ResolutionEffect] because it names *the card
 *   chosen mid-resolution* — an object the definition's pure effect has no way to refer to, since it
 *   did not exist when the effect ran (ADR-004).
 */
data class LibraryReveal(
    val count: Int,
    val toHand: RevealedCardFilter,
    val toHandCount: Int = 1,
    val disposition: RevealDisposition = RevealDisposition.CHOSEN_TO_HAND_REST_TO_GRAVEYARD,
    val mandatory: Boolean = false,
    val entersWithCounters: EntersWithCounters? = null,
    val grantedUntilYourNextTurn: PersistentSet<Keyword> = persistentSetOf(),
) {
    init {
        require(count >= 1) { "CR 701.16: a reveal effect reveals at least one card, was $count" }
        require(toHandCount in 1..count) {
            "CR 701.16: a reveal effect keeps between 1 and the revealed $count cards, was $toHandCount"
        }
        val ontoBattlefield = disposition == RevealDisposition.CHOSEN_TO_BATTLEFIELD_REST_SHUFFLED
        require(ontoBattlefield || entersWithCounters == null) {
            "CR 614.1c: an enters-with-counters replacement needs the chosen card to enter the " +
                "battlefield, but this reveal puts it in a hand"
        }
        require(ontoBattlefield || grantedUntilYourNextTurn.isEmpty()) {
            "CR 611.2c: a continuous effect's affected object is a permanent, but this reveal puts " +
                "the chosen card in a hand"
        }
    }
}

/**
 * Where a [LibraryReveal] puts the cards it chose, and what becomes of the ones it did not
 * (CR 701.16). Additive, flagged core (`W11`).
 *
 * **The two halves are one axis because both printings couple them**, which is the call
 * [LibrarySearchDestination] makes for its reveal-and-destination pairing and for the same reason:
 * splitting them would multiply out into four combinations of which two have no printing and would be
 * untested branches of the distribution. Malevolent Rumble and Kruphix's Insight say "…and the rest of
 * the revealed cards into your graveyard"; Throne of the Dead Three names no rest at all, because
 * there is nothing to move — the cards it did not choose never left the library.
 */
enum class RevealDisposition {
    /**
     * "…put up to M matching cards into your hand and the rest of the revealed cards into your
     * graveyard" (CR 701.16) — Malevolent Rumble, Kruphix's Insight. The library is **not** shuffled:
     * every revealed card has left it.
     */
    CHOSEN_TO_HAND_REST_TO_GRAVEYARD,

    /**
     * "…put a creature card from among them onto the battlefield … Then shuffle." (CR 701.16,
     * CR 701.18) — Throne of the Dead Three.
     *
     * The chosen card goes to the battlefield under the revealing player's control; every other
     * revealed card **stays where it is**, on top of that player's library, and the library is then
     * shuffled through the match PRNG (ADR-006 — the shuffle consumes seeded entropy, so replay
     * reproduces the new order). "Then shuffle" is what makes the unchosen cards' fate matter: without
     * it the revealer would know their next nine draws.
     */
    CHOSEN_TO_BATTLEFIELD_REST_SHUFFLED,
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

    /**
     * A land card (CR 305.1) — the second half of Winding Way's "Choose creature or land". Additive
     * (`W8-D`).
     *
     * Narrower than [PERMANENT_CARD] and not a reuse of it: an artifact card is a permanent card and is
     * not a land card, so a Winding Way naming "land" must leave it in the graveyard. A *land creature*
     * would satisfy both members, which is correct — a card has a set of types, and Winding Way's
     * "cards of the chosen type" reads that set.
     */
    LAND_CARD,
}
