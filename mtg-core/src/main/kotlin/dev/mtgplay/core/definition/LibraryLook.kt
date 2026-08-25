package dev.mtgplay.core.definition

/**
 * A "look at some cards privately, then arrange them between the top of your library, the bottom of your
 * library, and your hand" part of a spell's resolution (CR 701.14, CR 701.17) — Preordain's scry 2,
 * Ponder's reorder-the-top-three, Impulse's one-to-hand-rest-to-bottom, and Brainstorm's
 * two-from-hand-on-top. Additive, flagged core (`FW-LIBLOOK`, docs/design/library-look.md).
 *
 * **Not [LibraryReveal].** The two clauses agree on a surface and disagree on everything the CR cares
 * about: a reveal (CR 701.16a) shows cards to *all* players and emits
 * [dev.mtgplay.core.event.GameEvent.CardsRevealed]; a look (CR 701.14a) is seen by its controller and by
 * no one else, and emits nothing. A reveal distributes to {hand, graveyard} with no ordering; a look
 * distributes to {hand, top, bottom} and the *ordering is the decision*. A reveal's keep is filtered and
 * optional; a look's is unfiltered and may be mandatory. They are separate clause types on purpose
 * (docs/design/library-look.md §6).
 *
 * Card-definition *declaration*; `mtg-rules` takes the pool, enumerates every legal arrangement (ADR-005),
 * pauses for the choice, applies it, and then runs [optionalShuffle] and [thenDraw]. Runs as the last part
 * of a spell's resolution — after the definition's ordinary [ResolutionEffect] — because the arrangement
 * needs a mid-resolution decision, which the engine orchestrates around the pure effect.
 *
 * @property mode which oracle pattern this clause is: what the pool is and which arrangements are legal.
 * @property optionalShuffle whether a "you may shuffle" (CR 601.3b) follows the arrangement — Ponder's.
 *   The shuffle draws from the match-owned PRNG (ADR-006).
 * @property thenDraw how many cards to draw **after** the look — Preordain's and Ponder's "Draw a card."
 *   Zero for a clause with no trailing draw. A draw that comes *before* the look (Brainstorm's "Draw three
 *   cards, then …") is the card's ordinary [ResolutionEffect] instead, which the engine runs first.
 */
data class LibraryLook(
    val mode: LibraryLookMode,
    val optionalShuffle: Boolean = false,
    val thenDraw: Int = 0,
) {
    init {
        require(thenDraw >= 0) { "CR 121.1: a draw count is non-negative, was $thenDraw" }
    }
}

/**
 * Where the cards a [LibraryLook] arranges come from (CR 701.14a). Publicly observable at the table — an
 * opponent sees which zone the cards were taken from — so it is carried on the per-seat view, unlike the
 * cards themselves.
 */
enum class LibraryLookSource {
    /** The top of the looking player's own library (CR 401): scry, Ponder, Impulse. */
    TOP_OF_LIBRARY,

    /** The looking player's own hand (CR 402): Brainstorm's "two cards from your hand". */
    HAND,
}

/**
 * Which oracle pattern a [LibraryLook] follows: what its pool is, and which arrangements of that pool are
 * legal. One member per pattern, sealed so `mtg-rules` interprets it exhaustively; the enumeration of legal
 * arrangements per member — and the closed-form option count of each — is docs/design/library-look.md §4.2.
 *
 * A wider mode is the extension point, and one of the three docs/design/library-look.md §12 named has since
 * been taken: [RevealMatchingToHandRestToBottom] is that note's "a filter on the keep", added by the packet
 * that encoded the cards it named. **Surveil** (CR 701.44 — a look whose rest goes to the *graveyard*)
 * remains absent, and remains a mode question rather than a carrier question: it is this hierarchy plus a
 * fourth destination in the arrangement, and no encoded card needs it. Each absence is documented rather
 * than silently approximated.
 */
sealed interface LibraryLookMode {
    /** How many cards the pool holds at most — fewer if the source zone is short (CR 701: do as much as possible). */
    val count: Int

    /** Which zone the pool is taken from. */
    val source: LibraryLookSource

    /**
     * Scry [count] (CR 701.17a): "look at the top [count] cards of your library, then put any number of
     * them on the bottom of your library in any order and the rest on top of your library in any order."
     * Preordain's two. The partition is free — all-bottom and all-top are both legal — so the outcome
     * space is `(count + 1)!` arrangements.
     */
    data class Scry(
        override val count: Int,
    ) : LibraryLookMode {
        override val source: LibraryLookSource get() = LibraryLookSource.TOP_OF_LIBRARY

        init {
            require(count >= 1) { "CR 701.17a: scry N looks at at least one card, was $count" }
        }
    }

    /**
     * "Look at the top [count] cards of your library, then put them back in any order" — Ponder's three.
     * Every card returns to the top; only the ordering is chosen, so the outcome space is `count!`.
     */
    data class ReorderTop(
        override val count: Int,
    ) : LibraryLookMode {
        override val source: LibraryLookSource get() = LibraryLookSource.TOP_OF_LIBRARY

        init {
            require(count >= 1) { "CR 701.14a: a look looks at at least one card, was $count" }
        }
    }

    /**
     * "Look at the top [count] cards of your library. Put one of them into your hand and the rest on the
     * bottom of your library in any order" — Impulse's four, Sea Gate Oracle's two. The keep is
     * **mandatory**: no arrangement with an empty hand is enumerated, so the illegal decline that
     * [LibraryReveal.toHandCount]'s "up to" allows does not exist as an index (ADR-005). A pool short of
     * one card keeps nothing, because there is nothing to keep.
     */
    data class OneToHandRestToBottom(
        override val count: Int,
    ) : LibraryLookMode {
        override val source: LibraryLookSource get() = LibraryLookSource.TOP_OF_LIBRARY

        init {
            require(count >= 1) { "CR 701.14a: a look looks at at least one card, was $count" }
        }
    }

    /**
     * "Put [count] cards from your hand on top of your library in any order" — Brainstorm's two. The pool
     * is the whole hand and exactly [count] of it are placed, topmost first; every other card stays in the
     * hand in its existing order. Each placed card changes zone, so it is reborn with a fresh object id
     * (CR 400.7). A hand shorter than [count] places as many as it holds.
     */
    data class HandToTop(
        override val count: Int,
    ) : LibraryLookMode {
        override val source: LibraryLookSource get() = LibraryLookSource.HAND

        init {
            require(count >= 1) { "CR 701.14a: a hand-to-top placement places at least one card, was $count" }
        }
    }

    /**
     * "Look at the top [count] cards of your library. You may reveal *&lt;up to [maxToHand]&gt;* card(s)
     * matching [toHand] from among them and put them into your hand. Put the rest on the bottom of your
     * library in any order." Ancient Stirrings' five-deep colorless find, Augur of Bolas' three-deep
     * instant-or-sorcery, Lead the Stampede's five-deep any-number-of-creatures.
     *
     * **A look with a filtered keep — the `library-look.md` §12 non-goal, filled in.** That note left "a
     * filter on the keep" out of `FW-LIBLOOK` and named these exact cards; this is the member it pointed at.
     * It differs from [OneToHandRestToBottom] in the two ways the oracle text does: the keep is *filtered*
     * (only a matching card may be taken) and it is *optional* (every printed instance says "You **may**
     * reveal"), so the empty-hand arrangement this mode's sibling refuses to enumerate is legal here and is
     * enumerated.
     *
     * **The kept cards are revealed, and the rest are not** (CR 701.16a versus CR 701.14a) — the one place a
     * clause of this framework is *partly* public. That is what the printed word "reveal" buys the opponent
     * and it is exactly as much as it buys them: `mtg-rules` emits
     * [dev.mtgplay.core.event.GameEvent.CardsRevealed] for the cards that go to the hand and nothing at all
     * for the cards that go to the bottom, whose identities and order stay private to the looking player.
     *
     * **"Any number" is [maxToHand] `== ` [count], and that is a modelling call rather than an evasion.**
     * The pool holds at most [count] cards, so "any number of creature cards from among them" and "up to
     * [count] creature cards from among them" admit precisely the same set of outcomes — the enumeration is
     * identical, which under ADR-005 means the two phrasings *are* the same decision. Encoding "any number"
     * as its own flag would add a field no branch could ever read differently. Only the display prompt
     * distinguishes them, and it does so off this equality.
     *
     * @property count how many cards to look at.
     * @property toHand which of the looked-at cards may be revealed and kept (CR 701.16a).
     * @property maxToHand the **maximum** number of matching cards that may be kept — one for "a colorless
     *   card", [count] for "any number of creature cards". Keeping fewer, including none, is always legal.
     */
    data class RevealMatchingToHandRestToBottom(
        override val count: Int,
        val toHand: RevealedCardFilter,
        val maxToHand: Int,
    ) : LibraryLookMode {
        override val source: LibraryLookSource get() = LibraryLookSource.TOP_OF_LIBRARY

        init {
            require(count >= 1) { "CR 701.14a: a look looks at at least one card, was $count" }
            require(maxToHand in 1..count) {
                "CR 701.16a: a filtered keep takes between 1 and the looked-at $count cards, was $maxToHand"
            }
        }
    }
}
