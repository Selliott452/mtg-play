package dev.mtgplay.acceptance.invariant

/**
 * The named game-state invariants the [InvariantChecker] verifies (PLAN.md §2.3).
 *
 * Each member is one property that must hold of every state the engine ever produces. The set
 * grows every phase — battlefield statuses beyond tap arrive in Phase 3 — so this enum is the
 * extension point: a new invariant is a new member plus its check, and nothing that already
 * exists is reshaped.
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

    /**
     * Every mana pool is empty in every state the checker observes (CR 500.4). The exact rule
     * enforced in P2.x: the checker only ever sees **paused** states (decision points and final
     * states), payment executes atomically inside a single transition, and P2.1 payment plans
     * are exact — produced mana is consumed in the same transition — so no pause outside a cast
     * can carry floating mana, and no mid-payment state is ever observed. Phase 5's triggered
     * mana abilities (Utopia Sprawl) introduce legitimate floating mana between the cast and
     * the end of the step; this invariant then gains that declared exception (CR 500.4 still
     * bounds it at each step's end).
     */
    MANA_POOL_EMPTY_AT_PAUSE,

    /**
     * Tapped is a battlefield-only status (CR 110.5): every object in a library, hand,
     * graveyard, the stack, or exile is untapped — an object reborn off the battlefield carries
     * no status memory (CR 400.7).
     */
    TAP_STATUS_SCOPE,

    /**
     * The turn's land-drop count stays within the CR 305.2 bound: 0 or 1 in P2.x. Nothing in
     * the MVP pool grants additional land plays, so a count above one is engine wrongness;
     * when an additional-land-play effect first arrives, this invariant gains that declared
     * exception alongside it.
     */
    LAND_DROP_BOUND,

    /**
     * Marked damage (CR 120.3d) is a non-negative, battlefield-only quantity: every object's
     * [dev.mtgplay.core.state.GameObject.damageMarked] is at least zero, and any object off the
     * battlefield has none — an object reborn off the battlefield carries no marked damage
     * (CR 400.7), and cleanup wears it off at turn's end (CR 514.2). Added in P3.1.
     */
    MARKED_DAMAGE_SCOPE,

    /**
     * The combat state (CR 506–511), when present, references only real battlefield creatures and
     * is internally consistent beyond what construction can check: every attacker and blocker is a
     * battlefield object, every block names a declared attacker, and every recorded
     * damage-assignment order is a permutation of exactly its attacker's blockers (CR 509.2).
     * Absent outside the combat phase. Added in P3.1.
     */
    COMBAT_REFERENCES_VALID,
}
