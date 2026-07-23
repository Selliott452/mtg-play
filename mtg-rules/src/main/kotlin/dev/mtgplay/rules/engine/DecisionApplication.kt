package dev.mtgplay.rules.engine

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption

/**
 * Applies [decision] to the paused [state] (the engine's `advance`): recomputes the pending
 * request from the state (ADR-004), validates the decision against it loudly, records the
 * answer on the deciding seat (which is what advances the [DecisionRequestId] ordinal —
 * ADR-006), and dispatches on the request kind.
 */
internal fun applyDecision(
    state: GameState,
    decision: Decision,
): AdvanceResult {
    val request =
        pendingDecisionRequest(state)
            ?: error("advance called on a state that is not paused at a decision point (ADR-004)")
    validateDecision(request, decision)
    val answered = state.updatePlayer(request.seat) { it.copy(decisionsAnswered = it.decisionsAnswered + 1) }
    return when (request) {
        is DecisionRequest.ChooseAction -> applyChosenAction(answered, request, decision)
        is DecisionRequest.ChooseDiscards -> applyChosenDiscards(answered, request, decision)
        is DecisionRequest.ChooseTargets -> applyChosenTargets(answered, request, decision)
        is DecisionRequest.ChoosePaymentPlan -> applyChosenPaymentPlan(answered, request, decision)
        is DecisionRequest.DeclareAttackers -> applyDeclareAttackers(answered, request, decision)
        is DecisionRequest.DeclareBlockers -> applyDeclareBlockers(answered, request, decision)
        is DecisionRequest.OrderBlockers -> applyOrderBlockers(answered, request, decision)
        is DecisionRequest.OrderTriggers -> applyChosenTriggerOrder(answered, request, decision)
        is DecisionRequest.ChooseYesNo -> applyChosenYesNo(answered, request, decision)
        is DecisionRequest.ChooseCardsToExile -> applyChosenCardsToExile(answered, request, decision)
        is DecisionRequest.ChooseReplacement -> {
            check(decision is Decision.SingleSelect) { "unreachable: decision shape was validated against the request" }
            applyChosenReplacement(answered)
        }
    }
}

private fun applyChosenYesNo(
    state: GameState,
    request: DecisionRequest.ChooseYesNo,
    decision: Decision,
): AdvanceResult {
    check(decision is Decision.SingleSelect) { "unreachable: decision shape was validated against the request" }
    // The only yes/no in the pool is madness's reflexive cast (CR 702.35b); the pending madness record
    // carries who and what. A yes/no with no pending madness is an engine defect.
    check(state.pendingMadness != null) { "a yes/no was answered with no pending madness cast (${request.card.name})" }
    return applyMadnessCastChoice(state, accept = decision.index == DecisionRequest.ChooseYesNo.ACCEPT)
}

private fun applyChosenCardsToExile(
    state: GameState,
    request: DecisionRequest.ChooseCardsToExile,
    decision: Decision,
): AdvanceResult {
    check(decision is Decision.MultiSelect) { "unreachable: decision shape was validated against the request" }
    return applyChosenExileCost(state, decision.indices.map { request.options[it].objectId })
}

private fun applyChosenTriggerOrder(
    state: GameState,
    request: DecisionRequest.OrderTriggers,
    decision: Decision,
): AdvanceResult {
    check(decision is Decision.MultiSelect) { "unreachable: decision shape was validated against the request" }
    return applyOrderTriggers(state, request.seat, decision.indices)
}

private fun applyChosenAction(
    state: GameState,
    request: DecisionRequest.ChooseAction,
    decision: Decision,
): AdvanceResult {
    check(decision is Decision.SingleSelect) { "unreachable: decision shape was validated against the request" }
    return when (val option = request.options[decision.index]) {
        PriorityOption.Pass -> applyPassPriority(state, request.seat)
        is PriorityOption.CastSpell ->
            beginCastGathering(state, request.seat, option.objectId, option.source, option.permission)
        is PriorityOption.PlayLand -> executePlayLand(state, request.seat, option.objectId)
    }
}

private fun applyChosenDiscards(
    state: GameState,
    request: DecisionRequest.ChooseDiscards,
    decision: Decision,
): AdvanceResult {
    check(decision is Decision.MultiSelect) { "unreachable: decision shape was validated against the request" }
    val objectIds = decision.indices.map { request.options[it].objectId }
    return discardSelectedCards(state, request.seat, objectIds)
}

/**
 * Discards [objectIds] one at a time through the replacement framework (CR 614/616), then continues the
 * cleanup step (CR 514.1 -> 514.2 -> 514.3). A discard whose replacements need a CR 616.1 ordering
 * choice suspends here; that only happens for a fixture card with two or more discard replacements, so a
 * later card still to discard after such a suspension is an unsupported corner and fails loudly (no real
 * MVP card produces it).
 */
private fun discardSelectedCards(
    state: GameState,
    seat: PlayerId,
    objectIds: List<ObjectId>,
): AdvanceResult {
    if (objectIds.isEmpty()) return cleanupStep(state)
    return when (val outcome = beginDiscard(state, seat, objectIds.first())) {
        is DiscardOutcome.Completed -> discardSelectedCards(outcome.state, seat, objectIds.drop(1))
        is DiscardOutcome.NeedsReplacementChoice -> {
            require(objectIds.size == 1) {
                "CR 616.1: a discard needing a replacement choice must be the last of the batch; no MVP card " +
                    "produces two or more discard replacements, so a mid-batch choice is unsupported"
            }
            AdvanceResult.NeedsDecision(outcome.state, pendingReplacementRequest(outcome.state))
        }
    }
}

private fun applyChosenTargets(
    state: GameState,
    request: DecisionRequest.ChooseTargets,
    decision: Decision,
): AdvanceResult {
    check(decision is Decision.SingleSelect) { "unreachable: decision shape was validated against the request" }
    return applyChosenTarget(state, request.options[decision.index])
}

private fun applyChosenPaymentPlan(
    state: GameState,
    request: DecisionRequest.ChoosePaymentPlan,
    decision: Decision,
): AdvanceResult {
    check(decision is Decision.SingleSelect) { "unreachable: decision shape was validated against the request" }
    return executeCastPipeline(state, request.options[decision.index])
}
