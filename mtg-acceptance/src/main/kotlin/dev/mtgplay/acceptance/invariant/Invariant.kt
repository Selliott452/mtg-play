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
     * can carry floating mana, and no mid-payment state is ever observed. Triggered mana abilities
     * (Utopia Sprawl, CR 605.1b) introduce legitimate floating mana between the cast and the end of
     * the step; as of P6.2b (when the first such card is encoded) this invariant carries that
     * declared exception — a seat controlling a triggered-mana-ability source may hold floating mana
     * at a pause — while CR 500.4 still bounds it at each step's end and every other deck stays exact.
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

    /**
     * No battlefield creature sits at an observed pause with a met death condition (CR 704.5f/g):
     * every creature has toughness greater than 0 and marked damage strictly below its toughness.
     * State-based actions run to quiescence *before* any player receives priority (CR 704.3), so a
     * creature that would die has already gone to its graveyard by the time the checker sees a
     * decision-point state — a lingering doomed creature means the death state-based action failed
     * to fire. Added in P3.2.
     *
     * The check reads the **layered** toughness (CR 613 sublayer 7c) from P4.1 on — the same
     * in-game value combat and the death SBA read — so an Aura-buffed creature is measured
     * correctly; without a P/T modification it equals printed toughness.
     *
     * **Game-over exemption (P4.2).** This premise holds only while the game is still running. When
     * a player-loss state-based action is applicable (CR 704.5a life 0 or less, CR 704.5c empty-
     * library draw), the game ends immediately (CR 104.2a) and that same check's creature-death
     * actions are left unperformed — the loss is resolved first and the deaths are moot
     * (StateBasedActions.performBatch). A combat-damage step that drops a player to 0 *and* leaves a
     * blocked creature with lethal marked damage in the same batch is exactly this case (surfaced by
     * the P4.2 aura corpus, where trample + dynamic toughness make it common). So the check is a
     * no-op once a player loss is pending: a lethal creature in the final game-over state is correct,
     * not a failed SBA. It still fires on every non-final pause, where a lethal creature is a real bug.
     */
    CREATURE_LETHALITY_RESOLVED,

    /**
     * An Aura's attachment ([dev.mtgplay.core.state.GameObject.attachedTo]) is well-formed at every
     * observed pause (CR 303.4, CR 704.5m). Added in P4.1. Three properties: attachment is a
     * battlefield-only status (null off the battlefield, CR 400.7); a battlefield attachment names a
     * current battlefield object; and only an Aura carries one. Because the checker sees only paused
     * states — where state-based actions have run to quiescence (CR 704.3) — it tolerates no
     * dangling attachment: a stale reference means the CR 704.5m fall-off failed to fire. The
     * transient mid-transition dangle (between an enchanted creature's death and the next SBA check)
     * is never observed.
     *
     * **Game-over exemption (P4.2).** Like [CREATURE_LETHALITY_RESOLVED], the quiescence premise
     * holds only while the game is still running: once a player-loss state-based action is applicable
     * the game ends (CR 104.2a) and a batch's Aura fall-offs are left unperformed alongside the
     * creature deaths, so a dangling Aura in the final game-over state is correct. The check is a
     * no-op once a player loss is pending, and fires on every non-final pause as before.
     */
    ATTACHMENT_INTEGRITY,

    /**
     * A token exists only on the battlefield at an observed pause (CR 704.5d). Added in P5.1. A token
     * ("this object is a token" is `definitions[card] is TokenDefinition`) that reaches any other zone
     * ceases to exist as a state-based action, which runs to quiescence before any player receives
     * priority (CR 704.3), so the checker — which only ever sees paused or final states — tolerates no
     * off-battlefield token: one would mean the CR 704.5d cessation failed to fire. The transient
     * moment a dying token creature sits in a graveyard between two state-based-action checks is never
     * observed. A no-op once a player loss is pending: the game-over batch leaves the cessation
     * unperformed alongside the deaths (CR 104.2a), so an off-battlefield token in the final state is
     * correct.
     */
    TOKEN_ZONE_SCOPE,

    /**
     * Every fired-but-unplaced triggered ability is well-formed (CR 603.3). Added in P5.1. Two
     * properties of each [dev.mtgplay.core.state.PendingTrigger] in
     * [dev.mtgplay.core.state.GameState.pendingTriggers]: its controller is a seated player (CR
     * 603.3d), and it carries its own last-known information rather than a live reference — the source
     * is captured by value (id, card, controller), so a trigger stays valid even after its source has
     * left the battlefield (CR 603.10). The checker therefore does *not* require the source object to
     * still exist; it requires the trigger to be self-contained. Pending triggers are non-empty only
     * at an order-triggers pause (they are placed on the stack before any priority window opens), so at
     * most such pauses this guards the queue's sanity.
     */
    PENDING_TRIGGER_SANITY,

    /**
     * The madness marker ([dev.mtgplay.core.state.GameObject.awaitingMadness]) is well-formed at every
     * observed state (CR 702.35a–b). Added in P5.2. Three properties, which together are the exile-zone
     * integrity the marker guards: the marker is an **exile-only** status (a marked object off exile is
     * a leaked marker, CR 400.7, like tapped off the battlefield); a marked exile object always has its
     * matching reflexive machinery — a pending or on-stack madness trigger whose subject is the object,
     * or the pending-madness yes/no about it — because the marker and that machinery are created and
     * destroyed together (a marked object with neither is an orphaned marker); and, conversely, a
     * pending-madness record always names a marked exile object. Unmarked exile objects (flashback- and
     * escape-exiled cards) are unconstrained — exile legitimately holds inert cards.
     */
    MADNESS_MARKER_SANITY,

    /**
     * The pre-game mulligan phase ([dev.mtgplay.core.state.GameState.pendingMulligan]) is exclusive
     * and well-formed (CR 103.4/103.5). Added in P6.1. Whenever the phase is running: its decider is a
     * seated player; no player holds priority and the stack is empty (the phase precedes the game); and
     * no in-game pending transition — a cast, fired triggers, a madness yes/no, or a replacement choice
     * — coexists with it. Together these pin the phase as a distinct, priority-free pre-game position, so
     * the mulligan pause can never be confused with an in-game one.
     */
    MULLIGAN_PHASE_SANITY,

    /**
     * The plotted-turn marker ([dev.mtgplay.core.state.GameObject.plottedTurn]) is an **exile-only**
     * status (CR 702.140). Added in P6.2a. A plotted card waits in exile until it is cast for free on a
     * later turn; the marker records when it was plotted and is meaningless anywhere else — an object
     * reborn off exile carries none (CR 400.7), like the madness marker. A set marker off exile is a
     * leaked status.
     */
    PLOT_MARKER_SCOPE,

    /**
     * The as-enters chosen colour ([dev.mtgplay.core.state.GameObject.chosenColor]) is a
     * **battlefield-only** status (CR 614.12). Added in P6.2a. The colour Utopia Sprawl chose as it
     * entered is fixed on the battlefield permanent and read by its triggered mana ability; an object
     * reborn off the battlefield carries none (CR 400.7), like tapped and attachment. A set colour off
     * the battlefield is a leaked status.
     */
    CHOSEN_COLOUR_SCOPE,

    /**
     * Every P6.2c mid-resolution pause is well-formed (CR 601.2c/601.3b/701.18). Added in P6.2c. The
     * optional cost-then-draw ([dev.mtgplay.core.state.GameState.pendingOptionalCostDraw], Highway Robbery),
     * the mandatory resolution discard ([dev.mtgplay.core.state.GameState.pendingResolutionDiscard], Faithless
     * Looting), and the library search ([dev.mtgplay.core.state.GameState.pendingLibrarySearch], Ash Barrens)
     * each hang on a resolving object: whenever one is open, its decider is a seated player and the resolving
     * spell or activated ability is still on the stack (an empty stack would mean the pause outlived its
     * object). Cheap sanity guarding the new pending positions the fingerprint also digests.
     *
     * `FW-COUNTER` adds the unless-pay pause
     * ([dev.mtgplay.core.state.GameState.pendingCounterPayment], Force Spike) with the same two
     * properties plus one of its own: the spell it would counter is **still on the stack**. That is the
     * whole of what the pause hangs on — the CR 608.2b re-check already ran, so a target that has gone
     * means the engine entered the pause for a counter that should have fizzled. It is also the one
     * pause whose decider is normally *not* the resolving object's controller, so "decider is seated"
     * is a real check here rather than a restatement.
     */
    PENDING_RESOLUTION_SANITY,

    /**
     * Every ability that targets is well-formed, on the stack and at the CR 603.3d placement pause.
     * Added with `FW-ABILTGT` (docs/design/targeted-abilities.md §8). An ability entry's target count
     * matches its [dev.mtgplay.core.definition.TargetSpec]'s arity — none for
     * [dev.mtgplay.core.definition.TargetSpec.None], at most one otherwise — with the deliberate
     * exception that a *triggered* ability may carry **zero** targets while targeting (CR 603.3d: no
     * legal target existed as it was put on the stack; CR 608.2b then removes it doing nothing). The
     * same emptiness on an *activated* ability is a violation, because CR 601.2c forbids activating one
     * with no legal target — this is where that asymmetry is machine-checked. And whenever
     * [dev.mtgplay.core.state.GameState.pendingTriggerTargets] is open, its controller is seated with a
     * pending trigger that genuinely targets, and no cast or activation gathering coexists.
     */
    ABILITY_TARGET_SANITY,

    /**
     * Every permanent's counter multiset is well-formed (CR 122). Added with `FW-COUNTERS`, which is
     * the packet that gave [dev.mtgplay.core.state.GameObject.counters] its meaning — new state,
     * new property, same packet. Three arms: every recorded multiplicity is strictly positive
     * (CR 122.1 — a kind an object has none of is absent, not present with a zero, which is what
     * keeps equal positions comparing equal and hashing alike); no object off the battlefield carries
     * any (CR 122.2 — counters are not retained across a zone change, they cease to exist, so the
     * CR 400.7 rebirth must drop them); and no permanent has both a `+1/+1` and a `-1/-1` counter at
     * a pause (CR 704.5q — the state-based action annihilates the matching pairs whenever a player
     * would receive priority, so surviving pairs mean it did not fire).
     *
     * The third arm is the sibling of [CREATURE_LETHALITY_RESOLVED] and exists for the same reason: a
     * state-based action that silently fails to apply is invisible until something far away reads a
     * power it should never have seen.
     */
    COUNTER_SCOPE,
}
