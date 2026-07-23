package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingTrigger
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId

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
        val placed = putTriggerOnStack(state, group.single())
        priorityTo(placed, placed.turn.activePlayer)
    }
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
 * Puts one fired [trigger] on the stack as a [StackEntry.Ability] (CR 603.3, CR 113.3c) and removes it
 * from [GameState.pendingTriggers]. No card moves — an ability on the stack is not a card (CR 113.7a).
 */
internal fun putTriggerOnStack(
    state: GameState,
    trigger: PendingTrigger,
): GameState {
    val index = state.pendingTriggers.indexOf(trigger)
    require(index >= 0) { "trigger $trigger is not pending; only a pending trigger may be put on the stack" }
    return state
        .copy(pendingTriggers = state.pendingTriggers.removingAt(index))
        .updateStack { it.adding(StackEntry.Ability(trigger)) }
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
        options = group.map { DecisionRequest.OrderTriggers.Option(it.sourceCard, triggerDescription(it)) },
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
 * Applies [controller]'s chosen ordering of their simultaneous triggers (CR 603.3b): puts them on the
 * stack in the [order] the controller picked — index 0 first (resolving last of the group), the last
 * on top (resolving first) — then resumes the checkpoint toward the active player's window.
 */
internal fun applyOrderTriggers(
    state: GameState,
    controller: PlayerId,
    order: List<Int>,
): AdvanceResult {
    val group = state.pendingTriggers.filter { it.controller == controller }
    val placed = order.fold(state) { current, index -> putTriggerOnStack(current, group[index]) }
    return priorityTo(placed, placed.turn.activePlayer)
}

/** A short human description of a pending trigger, for the ordering decision's display (ADR-005). */
private fun triggerDescription(trigger: PendingTrigger): String =
    when (trigger.ability.condition) {
        TriggerCondition.EnteredBattlefieldSelf -> "enters-the-battlefield"
        TriggerCondition.PutIntoGraveyardFromBattlefieldSelf -> "leaves-the-battlefield"
        TriggerCondition.EnchantedCreatureDealsDamage -> "enchanted-creature-deals-damage"
        TriggerCondition.SpellCast -> "spell-cast"
    }
