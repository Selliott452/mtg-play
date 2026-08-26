package dev.mtgplay.rules.engine

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingTrigger
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/*
 * Putting fired triggers on the stack (CR 603.3b): when a player would receive priority — after
 * state-based actions settle (CR 704.3) and before the priority window opens — every pending trigger
 * is put on the stack in APNAP order. Each player, active player first, puts the triggers they control
 * on the stack; a player with two or more simultaneous triggers chooses the order (the [OrderTriggers]
 * decision, ADR-005). A single trigger needs no choice and is placed automatically, so a pause with
 * pending triggers always means the APNAP-first controller has two or more to order.
 *
 * After every pending trigger is placed, a fresh priority round opens for the active player (CR
 * 603.3b). The caster-retains-priority nuance for a cast trigger fired on an opponent's turn
 * (Guttersnipe, P6) — where priority should return to the caster, not the active player — is a
 * documented P6 follow-up; no MVP mainboard card fires a trigger outside a checkpoint already heading
 * to the active player.
 *
 * **Targets are chosen here, one ability at a time (CR 603.3d).** A triggered ability that targets
 * chooses its targets *as it is put on the stack* — not when it fires, and not when it resolves. So
 * placement is a drain rather than a fold: the CR 603.3b ordering answer **rewrites**
 * [GameState.pendingTriggers] into the chosen placement order, and [placeOrderedTriggers] then takes the
 * front of the controller's group repeatedly, suspending for a [DecisionRequest.ChooseTargets] before
 * any ability that has a legal target. The surviving list order *is* the remaining placement order,
 * which is what makes a mid-batch pause resumable from the state alone (ADR-004). The target request and
 * its answer live in `TriggerTargeting.kt`; see docs/design/targeted-abilities.md §3.
 */

/** The minimum simultaneous triggers a single controller must have to need an ordering choice. */
internal const val MINIMUM_ORDERED_TRIGGERS: Int = 2

/**
 * Places pending triggers on the stack (CR 603.3b) and then resumes toward the active player's
 * priority window. Called from [priorityTo] once state-based actions have settled and pending triggers
 * remain. Processes controllers in APNAP order: the APNAP-first controller with two or more triggers
 * suspends with an [DecisionRequest.OrderTriggers] choice (ADR-005); otherwise the single trigger is
 * put on the stack and the checkpoint is re-entered (which places the next controller's, or opens the
 * window when none remain).
 */
internal fun placePendingTriggers(state: GameState): AdvanceResult {
    val controller =
        apnapFirstPendingController(state)
            ?: error("placePendingTriggers requires at least one pending trigger, but there are none")
    val group = state.pendingTriggers.filter { it.controller == controller }
    return if (group.size >= MINIMUM_ORDERED_TRIGGERS) {
        AdvanceResult.NeedsDecision(state, orderTriggersRequest(state, controller, group))
    } else {
        // A single trigger needs no ordering choice; it is placed straight away — pausing first for its
        // CR 603.3d targets if it targets and any legal target exists.
        placeOrderedTriggers(state, controller)
    }
}

/**
 * Puts [controller]'s already-ordered pending triggers on the stack, front of the group first (CR
 * 603.3b), pausing before each that must choose targets (CR 603.3d). When the group is empty the
 * checkpoint resumes toward the recorded recipient (CR 601.2i — the caster after a cast trigger, not
 * blindly the active player), which places the next controller's group or opens the priority window.
 *
 * A targeting ability with **no** legal target is still put on the stack, carrying no targets, and does
 * nothing when it resolves (CR 603.3d, CR 608.2b) — the opposite of an activated ability, which cannot
 * be activated at all in that position (CR 601.2c). That asymmetry is why this path does not share a
 * "choose targets or abandon" helper with the activation path (docs/design/targeted-abilities.md §2.1).
 */
internal fun placeOrderedTriggers(
    state: GameState,
    controller: PlayerId,
): AdvanceResult {
    val next =
        state.pendingTriggers.firstOrNull { it.controller == controller }
            ?: return priorityTo(state, priorityRecipient(state))
    return triggerTargetPause(state, controller, next)
        ?: placeOrderedTriggers(putTriggerOnStack(state, next, persistentListOf()), controller)
}

/**
 * The APNAP-first player (CR 101.4) who controls at least one pending trigger — the active player if
 * they control any, otherwise the next in turn order, and so on — or `null` if no trigger is pending.
 * The order simultaneous triggers are put on the stack (CR 603.3b).
 */
internal fun apnapFirstPendingController(state: GameState): PlayerId? =
    apnapOrder(state).firstOrNull { seat -> state.pendingTriggers.any { it.controller == seat } }

/** The seats in APNAP order (CR 101.4): the active player, then the rest in turn order, wrapping. */
private fun apnapOrder(state: GameState): List<PlayerId> {
    val order = mutableListOf(state.turn.activePlayer)
    var next = state.seatAfter(state.turn.activePlayer)
    while (next != state.turn.activePlayer) {
        order += next
        next = state.seatAfter(next)
    }
    return order
}

/**
 * Puts one fired [trigger] on the stack as a [StackEntry.Ability] (CR 603.3, CR 113.3c) with the
 * [targets] its controller chose as it was placed (CR 603.3d), and removes it from
 * [GameState.pendingTriggers]. No card moves — an ability on the stack is not a card (CR 113.7a).
 * [targets] is empty for an untargeted ability, and also for a targeting one whose controller had no
 * legal choice.
 */
internal fun putTriggerOnStack(
    state: GameState,
    trigger: PendingTrigger,
    targets: PersistentList<Target>,
): GameState {
    val index = state.pendingTriggers.indexOf(trigger)
    require(index >= 0) { "trigger $trigger is not pending; only a pending trigger may be put on the stack" }
    return state
        .copy(pendingTriggers = state.pendingTriggers.removingAt(index))
        .updateStack { it.adding(StackEntry.Ability(trigger, targets)) }
        .emit(
            dev.mtgplay.core.event.GameEvent
                .TriggeredAbilityPutOnStack(trigger.controller, trigger.sourceCard),
        )
}

/**
 * The [DecisionRequest.OrderTriggers] request for [controller]'s simultaneous [group] of pending
 * triggers (CR 603.3b): the controller chooses the order to put them on the stack. A pure function of
 * the state, like every pending request (ADR-004) — [pendingDecisionRequest] re-derives the identical
 * request, and [applyOrderTriggers] re-derives the identical group.
 */
internal fun orderTriggersRequest(
    state: GameState,
    controller: PlayerId,
    group: List<PendingTrigger>,
): DecisionRequest.OrderTriggers =
    DecisionRequest.OrderTriggers(
        id = DecisionRequestId(controller, state.player(controller).decisionsAnswered),
        options =
            group.map {
                DecisionRequest.OrderTriggers.Option(it.sourceCard, describeCondition(it.ability.condition))
            },
    )

/**
 * The pending [DecisionRequest.OrderTriggers] derived from [state] alone (ADR-004): the APNAP-first
 * controller with pending triggers orders their two or more simultaneous triggers. A single pending
 * trigger is placed automatically without a decision, so reaching here with fewer than two is an
 * engine defect (a pause should never carry an auto-placeable trigger).
 */
internal fun pendingOrderTriggersRequest(state: GameState): DecisionRequest.OrderTriggers {
    val controller =
        apnapFirstPendingController(state)
            ?: error("a pending-trigger pause requires at least one pending trigger")
    val group = state.pendingTriggers.filter { it.controller == controller }
    require(group.size >= MINIMUM_ORDERED_TRIGGERS) {
        "CR 603.3b: a pending-trigger pause implies a controller with two or more simultaneous " +
            "triggers to order; a single trigger is placed automatically, so ${group.size} is an engine defect"
    }
    return orderTriggersRequest(state, controller, group)
}

/**
 * Applies [controller]'s chosen ordering of their simultaneous triggers (CR 603.3b): index 0 is put on
 * the stack first (so it sits at the bottom of this batch and resolves last), the last index on top
 * (resolving first).
 *
 * The answer **rewrites [GameState.pendingTriggers] into the chosen placement order** rather than
 * placing anything itself, and hands off to [placeOrderedTriggers]. That is what lets a targeting
 * trigger (CR 603.3d) suspend part-way through the batch without losing the order: the remaining order
 * is the surviving list order, so re-deriving from the paused state reproduces it (ADR-004). With no
 * targeting trigger in the group the observable behaviour is unchanged — all of them are placed in the
 * chosen order and the checkpoint resumes.
 */
internal fun applyOrderTriggers(
    state: GameState,
    controller: PlayerId,
    order: List<Int>,
): AdvanceResult {
    val group = state.pendingTriggers.filter { it.controller == controller }
    require(order.sorted() == group.indices.toList()) {
        "CR 603.3b: a trigger order permutes all ${group.size} of the controller's triggers, was $order"
    }
    return placeOrderedTriggers(reorderPendingTriggers(state, controller, order.map { group[it] }), controller)
}

/**
 * Rewrites [GameState.pendingTriggers] so [controller]'s triggers appear in [ordered] — the CR 603.3b
 * placement order just chosen — while every other controller's trigger keeps its slot. Slot
 * substitution rather than filter-and-append, so two indistinguishable triggers (same source, same
 * ability, same linked information) are handled positionally and no other controller's relative order
 * moves.
 */
private fun reorderPendingTriggers(
    state: GameState,
    controller: PlayerId,
    ordered: List<PendingTrigger>,
): GameState {
    val remaining = ordered.iterator()
    val rewritten =
        state.pendingTriggers
            .map { if (it.controller == controller) remaining.next() else it }
            .toPersistentList()
    return state.copy(pendingTriggers = rewritten)
}
