package dev.mtgplay.acceptance.invariant

/**
 * The named game-state invariants the [InvariantChecker] verifies (PLAN.md §2.3).
 *
 * Each member is one property that must hold of every state the engine ever produces. The set
 * grows every phase — mana-pool emptiness in Phase 2, battlefield statuses in Phase 3 — so this
 * enum is the extension point: a new invariant is a new member plus its check, and nothing that
 * already exists is reshaped.
 */
enum class Invariant {
    /**
     * Every game object occupies exactly one zone: no [dev.mtgplay.core.identity.ObjectId]
     * appears in more than one zone across all libraries, hands, graveyards, the battlefield, the
     * stack, and exile (CR 400.7 — an object exists in exactly one zone at a time).
     */
    ZONE_CONSERVATION,

    /**
     * The multiset of printed cards ([dev.mtgplay.core.identity.CardRef]) across all zones never
     * changes over a game: no card is created or destroyed. True for the whole engine until token
     * creation arrives in Phase 5, which is when this invariant gains a declared exception.
     */
    CARD_CONSERVATION,

    /**
     * At most one player holds priority at a time (CR 117.1a): no two seats are simultaneously
     * [dev.mtgplay.core.state.PriorityStatus.HOLDS_PRIORITY].
     */
    PRIORITY,

    /**
     * A recorded empty-library draw attempt is honest: whenever a seat's
     * [dev.mtgplay.core.state.PlayerState.attemptedDrawFromEmptyLibrary] flag is set, that seat's
     * library is in fact empty (CR 704.5c — the loss acts on the recorded attempt, never inferred
     * from emptiness alone).
     */
    DRAW_FAILURE_HONESTY,

    /**
     * Object ids and bookkeeping counters stay within their declared bounds: every
     * [dev.mtgplay.core.identity.ObjectId] in any zone is strictly below the allocation counter
     * (CR 400.7), and every seat's answered-decision count is non-negative.
     */
    ID_SANITY,
}
