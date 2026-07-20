package dev.mtgplay.rules.engine

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
    }
}

private fun applyChosenAction(
    state: GameState,
    request: DecisionRequest.ChooseAction,
    decision: Decision,
): AdvanceResult {
    check(decision is Decision.SingleSelect) { "unreachable: decision shape was validated against the request" }
    return when (request.options[decision.index]) {
        PriorityOption.Pass -> applyPassPriority(state, request.seat)
    }
}

private fun applyChosenDiscards(
    state: GameState,
    request: DecisionRequest.ChooseDiscards,
    decision: Decision,
): AdvanceResult {
    check(decision is Decision.MultiSelect) { "unreachable: decision shape was validated against the request" }
    val afterDiscards =
        decision.indices.fold(state) { current, index ->
            discardCard(current, request.seat, request.options[index].objectId)
        }
    // Continue the cleanup step the discard belongs to (CR 514.1 -> 514.2 -> 514.3).
    return cleanupStep(afterDiscards)
}
