package dev.mtgplay.rules.engine

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingTrigger
import dev.mtgplay.core.state.PendingTriggerTargets
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import kotlinx.collections.immutable.toPersistentList

/*
 * The CR 603.3d target choice of a triggered ability, split from TriggerPlacement.kt so each file stays
 * within its function budget.
 *
 * "The ability's controller chooses targets for it… as the ability is put on the stack." That moment is
 * inside [placeOrderedTriggers], between the state-based-action check and the priority window — the one
 * pause the engine takes with **no priority round open and possibly an empty stack**. It is therefore
 * neither a gathering pause (nobody holds priority *for it*) nor a mid-resolution pause (nothing is
 * resolving), and it gets its own branch in [pendingDecisionRequest].
 *
 * The request is a plain target request: CR 601.2c is the rule for a cast, an
 * activation (via CR 602.2b) and a trigger placement (via CR 603.3d) alike, so the three share one
 * enumerated decision and drivers, the protocol, and the ADR-005 enumeration probe need no new member.
 * Which flow an answer belongs to is read from the open pending record, exactly as the yes/no and
 * payment-plan requests already do (docs/design/targeted-abilities.md §4).
 */

/**
 * The CR 603.3d target-choice pause for [next], the trigger [placeOrderedTriggers] is about to put on the
 * stack, or `null` when it needs none — the ability targets nothing, or it targets and its controller has
 * **no legal target**, in which case it goes on the stack target-less and does nothing when it resolves
 * (CR 608.2b). The second case is what distinguishes a triggered ability from an activated one, which
 * simply cannot be activated without a legal target (CR 601.2c).
 */
internal fun triggerTargetPause(
    state: GameState,
    controller: PlayerId,
    next: PendingTrigger,
): AdvanceResult? {
    val spec = next.ability.targetSpec
    // CR 113.7b: the trigger's source, carried on the pending trigger as last known information — the
    // source may already have left the battlefield by the time its own leave-the-battlefield trigger
    // chooses targets. CR 702.16b tests a protected object against exactly these characteristics.
    if (targetChoiceIsVacuous(state, spec, controller, Chooser.Ability(next.sourceCard))) return null
    val paused =
        state.copy(pendingTriggerTargets = PendingTriggerTargets(controller, next.sourceId, next.sourceCard))
    return AdvanceResult.NeedsDecision(paused, pendingTriggerTargetsRequest(paused))
}

/**
 * The triggered ability [state] is choosing targets for (CR 603.3d): the front of the placing
 * controller's group in [GameState.pendingTriggers], whose list order *is* the CR 603.3b placement
 * order. Derived rather than stored, so nothing can drift across the reorder
 * ([dev.mtgplay.core.state.PendingTriggerTargets]).
 */
private fun placingTrigger(state: GameState): PendingTrigger {
    val pending =
        state.pendingTriggerTargets
            ?: error("CR 603.3d: no triggered ability is choosing targets")
    return state.pendingTriggers.firstOrNull { it.controller == pending.controller }
        ?: error(
            "CR 603.3d: targets are chosen as an ability is put on the stack, but no trigger is pending " +
                "for ${pending.controller}",
        )
}

/**
 * The target request the open CR 603.3d placement is waiting on — [DecisionRequest.ChooseTargets] for a
 * one-target ability, [DecisionRequest.ChooseMultipleTargets] for an "up to N" one. A pure function of
 * the state (ADR-004): the deciding seat is the ability's controller — **not** necessarily the player
 * who will receive priority, exactly as for the CR 603.3b ordering choice. Only surfaced when a legal
 * target exists ([placeOrderedTriggers] places a target-less ability outright), so both kinds' non-empty
 * options requirement always holds.
 */
internal fun pendingTriggerTargetsRequest(state: GameState): DecisionRequest {
    val pending =
        state.pendingTriggerTargets
            ?: error("CR 603.3d: no triggered ability is choosing targets")
    val trigger = placingTrigger(state)
    return targetRequest(
        id = DecisionRequestId(pending.controller, state.player(pending.controller).decisionsAnswered),
        cardObjectId = pending.sourceId,
        card = pending.sourceCard,
        spec = trigger.ability.targetSpec,
        // CR 113.7b/702.16b: enumerated against the trigger's source, the same one [triggerTargetPause]
        // asked the vacuity question with.
        options =
            legalTargets(
                state,
                trigger.ability.targetSpec,
                pending.controller,
                Chooser.Ability(pending.sourceCard),
            ),
    )
}

/**
 * Records [targets] on the triggered ability being placed (CR 603.3d), puts it on the stack, and resumes
 * the CR 603.3b drain — which places the rest of this controller's ordered group, then the next
 * controller's, then opens the priority window. The whole placement, choice included, is one transition:
 * a pending trigger with settled targets is never a state the engine pauses in, which is why
 * [PendingTrigger] carries no targets field.
 */
internal fun applyChosenTriggerTarget(
    state: GameState,
    targets: List<Target>,
): AdvanceResult {
    val pending =
        state.pendingTriggerTargets
            ?: error("CR 603.3d: no triggered ability is choosing targets")
    val trigger = placingTrigger(state)
    val placed =
        putTriggerOnStack(state.copy(pendingTriggerTargets = null), trigger, targets.toPersistentList())
    return placeOrderedTriggers(placed, pending.controller)
}
