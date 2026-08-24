package dev.mtgplay.rules.engine

import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest

/*
 * Applying an answer to the "pick exactly one of these options" family
 * ([DecisionRequest.SingleOptionSelection]): a cast's target (CR 601.2c) or payment plan (CR 601.2g), a
 * trample assignment (CR 702.19e), an as-enters colour (CR 614.12), a replacement ordering (CR 616.1), or
 * a private look's arrangement (CR 701.17a). Split from DecisionApplication.kt, which owns the top-level
 * dispatch, so each file stays inside detekt's function budget.
 *
 * Two of these requests serve more than one engine flow, and the *open pending record* says which — the
 * idiom `applyChosenYesNo` uses for madness vs. the optional discard-then-draw. The branches are always
 * tested in the order `pendingDecisionRequest` derives them in, so an answer can never be routed to a flow
 * other than the one that was asked.
 */

/**
 * Applies one "pick exactly one of these options" answer, dispatching by kind. The decision's shape and
 * index range are already validated against the re-derived request (ADR-004), so the index is in range.
 */
internal fun applySingleOptionSelection(
    state: GameState,
    request: DecisionRequest.SingleOptionSelection,
    decision: Decision,
): AdvanceResult {
    check(decision is Decision.SingleSelect) { "unreachable: decision shape was validated against the request" }
    return when (request) {
        is DecisionRequest.ChooseTargets -> applyChosenTargets(state, request, decision)
        is DecisionRequest.ChoosePaymentPlan -> applyChosenPaymentPlan(state, request, decision)
        // The option index *is* the amount assigned to the defending player (options are 0..excess).
        is DecisionRequest.AssignTrampleDamage ->
            applyTrampleAssignment(state, request, request.options[decision.index])
        is DecisionRequest.ChooseColor -> applyChosenColor(state, request.options[decision.index])
        is DecisionRequest.ChooseReplacement -> applyChosenReplacement(state)
        // CR 701.14a/701.17a: one index names a complete arrangement of the privately looked-at cards.
        is DecisionRequest.ChooseLibraryArrangement ->
            applyLibraryArrangement(state, request.options[decision.index])
    }
}

/**
 * Applies a chosen target (CR 601.2c). One request serves three flows — a cast (CR 601.2c), an
 * activation (CR 602.2b), and a triggered ability being put on the stack (CR 603.3d) — and the open
 * pending record says which.
 */
private fun applyChosenTargets(
    state: GameState,
    request: DecisionRequest.ChooseTargets,
    decision: Decision.SingleSelect,
): AdvanceResult {
    val target = request.options[decision.index]
    return when {
        state.pendingCast != null -> applyChosenTarget(state, target)
        state.pendingActivation != null -> applyChosenActivationTarget(state, target)
        state.pendingTriggerTargets != null -> applyChosenTriggerTarget(state, target)
        else -> error("a target was chosen with no cast, activation, or trigger placement awaiting one: $request")
    }
}

/**
 * Applies a chosen payment plan: it settles a cast (CR 601.2g), the plot special action (CR 702.140), or
 * an activated ability's mana cost (CR 602.2g), again by which pending record is open.
 */
private fun applyChosenPaymentPlan(
    state: GameState,
    request: DecisionRequest.ChoosePaymentPlan,
    decision: Decision.SingleSelect,
): AdvanceResult {
    val plan = request.options[decision.index]
    return when {
        state.pendingPlot != null -> executePlot(state, plan)
        state.pendingActivation != null -> executeActivation(state, plan)
        else -> executeCastPipeline(state, plan)
    }
}
