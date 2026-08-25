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
        checkNotNull(pendingDecisionRequest(state)) {
            "advance called on a state that is not paused at a decision point (ADR-004)"
        }
    validateDecision(request, decision)
    val answered = state.updatePlayer(request.seat) { it.copy(decisionsAnswered = it.decisionsAnswered + 1) }
    return when (request) {
        is DecisionRequest.ChooseAction -> applyChosenAction(answered, request, decision)
        is DecisionRequest.DeclareAttackers -> applyDeclareAttackers(answered, request, decision)
        is DecisionRequest.DeclareBlockers -> applyDeclareBlockers(answered, request, decision)
        // CR 601.2c / 601.2g / 702.19e / 614.12 / 616.1 / 701.17a: the "pick exactly one option"
        // requests dispatch by kind.
        is DecisionRequest.SingleOptionSelection -> applySingleOptionSelection(answered, request, decision)
        // CR 509.2 / 603.3b: the two ordering answers (blocker order, trigger order) dispatch by kind.
        is DecisionRequest.PermutationSelection -> applyPermutation(answered, request, decision)
        is DecisionRequest.ChooseYesNo -> applyChosenYesNo(answered, request, decision)
        // CR 514.1 / 601.2b/h / 602.2b: the fixed-size subset selections dispatch by kind.
        is DecisionRequest.SizedSelection -> applySizedSelection(answered, request, decision)
        is DecisionRequest.ChoiceCountSelection -> {
            check(decision is Decision.SingleSelect) { "unreachable: decision shape was validated against the request" }
            // CR 701.16/601.3b/701.18: dispatch by kind; the trailing opt-out index means keep/decline/find none.
            applyChoiceCountSelection(answered, request, decision.index)
        }
        // CR 103.4/103.5: the pre-game mulligan decisions share a dispatcher.
        is DecisionRequest.MulliganRequest -> applyMulliganDecision(answered, request, decision)
    }
}

/**
 * Applies one "choose one, or opt out" single-select (CR 701.16 / 601.3b / 701.18) — a reveal keep-one, a
 * cost-then-draw mode, or a library find-one — dispatching by kind. The [index] is the chosen option, or the
 * trailing opt-out index (past the last option), which each flow resolves as its "none" (keep/decline/find
 * none). Split out so the main dispatch stays flat.
 */
private fun applyChoiceCountSelection(
    state: GameState,
    request: DecisionRequest.ChoiceCountSelection,
    index: Int,
): AdvanceResult =
    when (request) {
        is DecisionRequest.ChooseFromRevealed ->
            applyRevealSelection(state, request.options.getOrNull(index)?.objectId)
        is DecisionRequest.ChooseCostMode ->
            applyCostModeChoice(state, request.options.getOrNull(index))
        is DecisionRequest.ChooseFromLibrary ->
            applyLibrarySearchChoice(state, request.options.getOrNull(index)?.objectId)
    }

/**
 * Applies one fixed-size subset selection (CR 514.1 / 601.2b/h / 602.2b) — the cleanup discard, an
 * additional exile or sacrifice or discard cost, or an activated ability's discard — dispatching by
 * kind to its specific applier. Split out so the main dispatch stays flat.
 */
private fun applySizedSelection(
    state: GameState,
    request: DecisionRequest.SizedSelection,
    decision: Decision,
): AdvanceResult {
    check(decision is Decision.MultiSelect) { "unreachable: decision shape was validated against the request" }
    return when (request) {
        is DecisionRequest.ChooseDiscards ->
            discardSelectedCards(state, request.seat, decision.indices.map { request.options[it].objectId })
        is DecisionRequest.ChooseCardsToExile ->
            applyChosenExileCost(state, decision.indices.map { request.options[it].objectId })
        is DecisionRequest.ChooseSacrifices ->
            applyChosenSacrifices(state, decision.indices.map { request.options[it].objectId })
        is DecisionRequest.ChooseCardsToDiscardForCost ->
            applyChosenAdditionalDiscard(state, decision.indices.map { request.options[it].objectId })
        is DecisionRequest.ChooseSacrificesForCost ->
            applyChosenAdditionalSacrifice(state, decision.indices.map { request.options[it].objectId })
        is DecisionRequest.ChooseAbilitySacrifice ->
            applyChosenAbilitySacrifice(state, decision.indices.map { request.options[it].objectId })
        is DecisionRequest.ChooseAbilityDiscard ->
            applyChosenAbilityDiscard(state, decision.indices.map { request.options[it].objectId })
        is DecisionRequest.ChooseOptionalDiscard ->
            applyOptionalDiscardChoice(state, request.options[decision.indices.single()].objectId)
        is DecisionRequest.ChooseOptionalCostObject ->
            applyOptionalCostObject(state, request.options[decision.indices.single()].objectId)
        is DecisionRequest.ChooseResolutionDiscards ->
            applyResolutionDiscards(state, decision.indices.map { request.options[it].objectId })
    }
}

/**
 * Applies one pre-game mulligan decision (CR 103.4/103.5): the keep-or-mulligan choice, or the
 * bottom-cards multi-select. Split out so the main dispatch keeps one concern per branch.
 */
private fun applyMulliganDecision(
    state: GameState,
    request: DecisionRequest.MulliganRequest,
    decision: Decision,
): AdvanceResult =
    when (request) {
        is DecisionRequest.ChooseMulligan -> {
            check(decision is Decision.SingleSelect) { "unreachable: decision shape was validated against the request" }
            applyMulliganChoice(state, keep = decision.index == DecisionRequest.ChooseMulligan.KEEP)
        }
        is DecisionRequest.ChooseCardsToBottom -> {
            check(decision is Decision.MultiSelect) { "unreachable: decision shape was validated against the request" }
            applyBottomChoice(state, decision.indices.map { request.options[it].objectId })
        }
    }

private fun applyChosenYesNo(
    state: GameState,
    request: DecisionRequest.ChooseYesNo,
    decision: Decision,
): AdvanceResult {
    check(decision is Decision.SingleSelect) { "unreachable: decision shape was validated against the request" }
    val accept = decision.index == DecisionRequest.ChooseYesNo.ACCEPT
    // Three yes/no flows share this request: madness's reflexive cast (CR 702.35b), the optional
    // discard-then-draw clause (CR 601.3b), and a library look's optional shuffle (CR 601.3b, Ponder);
    // the pending record present says which. Tested in the order pendingDecisionRequest derives them in,
    // so an answer can never be routed to a flow other than the one that was asked.
    return when {
        state.pendingOptionalDiscardDraw != null -> applyOptionalDiscardYesNo(state, accept)
        state.pendingLibraryLook != null -> applyLibraryLookShuffle(state, accept)
        state.pendingMadness != null -> applyMadnessCastChoice(state, accept)
        else -> error("a yes/no was answered with no pending madness, discard, or look flow (${request.card.name})")
    }
}

/** Applies an ordering answer (CR 509.2 / 603.3b): the blocker order or the trigger order, by kind. */
private fun applyPermutation(
    state: GameState,
    request: DecisionRequest.PermutationSelection,
    decision: Decision,
): AdvanceResult =
    when (request) {
        is DecisionRequest.OrderBlockers -> applyOrderBlockers(state, request, decision)
        is DecisionRequest.OrderTriggers -> {
            check(decision is Decision.MultiSelect) { "unreachable: decision shape was validated against the request" }
            applyOrderTriggers(state, request.seat, decision.indices)
        }
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
        is PriorityOption.PlotCard -> beginPlot(state, request.seat, option.objectId)
        is PriorityOption.ActivateAbility ->
            beginActivation(state, request.seat, option.objectId, option.scope, option.abilityIndex)
    }
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
