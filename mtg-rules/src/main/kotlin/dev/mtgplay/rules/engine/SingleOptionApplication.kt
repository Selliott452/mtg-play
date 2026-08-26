package dev.mtgplay.rules.engine

import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
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
        // CR 601.2b: the option index names an *offered* value of X; the value itself is recorded.
        is DecisionRequest.ChooseXValue -> applyAnnouncedX(state, request.values[decision.index])
        // The option index *is* the amount assigned to the defending player (options are 0..excess).
        is DecisionRequest.AssignTrampleDamage ->
            applyTrampleAssignment(state, request, request.options[decision.index])
        // CR 614.12 / CR 609.4: two flows share this request and are told apart by which pending record
        // is open — the as-enters choice on an entering permanent, and the mid-resolution clause. The
        // disambiguation is the one five yes/no flows already use, and the order matches the one
        // `pendingDecisionRequest` derives them in.
        is DecisionRequest.ChooseColor ->
            if (state.pendingChosenColor != null) {
                applyChosenColorClause(state, request.options[decision.index])
            } else {
                applyChosenColor(state, request.options[decision.index])
            }
        is DecisionRequest.ChooseReplacement -> applyChosenReplacement(state)
        // CR 701.14a/701.17a: one index names a complete arrangement of the privately looked-at cards.
        is DecisionRequest.ChooseLibraryArrangement ->
            applyLibraryArrangement(state, request.options[decision.index])
        // CR 118.3a: index 0 declines and the spell is countered; any other index pays a plan in full.
        is DecisionRequest.ChooseCounterPayment ->
            applyCounterPayment(
                state,
                (request.options[decision.index] as? DecisionRequest.ChooseCounterPayment.Option.Pay)?.plan,
            )
        // CR 701.16a: the controller's pick from the opponent's revealed hand; the clause's outcome
        // (discard or linked exile) decides what happens to it.
        is DecisionRequest.ChooseRevealedHandCard ->
            applyHandRevealChoice(state, request.options[decision.index].objectId)
        // CR 608.2c: decline, tap, or untap the clause's target (`W8-G`, Sewer-veillance Cam).
        else -> applyResolutionClauseSelection(state, request, decision)
    }
}

/**
 * The tail of [applySingleOptionSelection]: the answers to the clauses a *resolving* object opens — a
 * tap-or-untap choice (CR 608.2c), an optional mana payment (CR 601.3b), a graveyard exile
 * (CR 701.3a), and a revealed-card type choice.
 *
 * Split out only so the dispatch stays inside detekt's complexity budget, the same shape as the splits
 * in `PendingDecision.kt`, `DecisionView.kt`, the CLI menu family, and the protocol codec. These four
 * are the arms that arrived last; there is no seam in the rules here. The `else` is exhaustive by
 * construction and fails loudly, because an answer silently dropped here would leave the engine paused
 * on a decision it had already been given.
 */
private fun applyResolutionClauseSelection(
    state: GameState,
    request: DecisionRequest.SingleOptionSelection,
    decision: Decision.SingleSelect,
): AdvanceResult =
    when (request) {
        is DecisionRequest.ChooseTapOrUntap -> applyTapOrUntapChoice(state, request.options[decision.index])
        // CR 601.3b: index 0 declines and nothing is drawn; any other index pays a plan in full, then draws.
        is DecisionRequest.ChooseOptionalManaPayment ->
            applyOptionalManaPayment(
                state,
                (request.options[decision.index] as? DecisionRequest.ChooseOptionalManaPayment.Option.Pay)?.plan,
            )
        // CR 701.3a/CR 601.3b: the deciding player's pick from their own graveyard. Every index but the
        // "you may exile" decline names a card, and only a named card runs a gated "if you do" half.
        is DecisionRequest.ChooseGraveyardCardToExile ->
            applyGraveyardExileChoice(state, request.options.getOrNull(decision.index)?.objectId)
        // CR 401.1: the owner's chosen depth for a permanent going into their library.
        is DecisionRequest.ChooseLibraryPosition ->
            applyLibraryPlacement(state, request.options[decision.index])
        // CR 701.40a: the explorer's destination for the revealed card, top of library or graveyard.
        is DecisionRequest.ChooseExploreDestination ->
            applyExplore(state, request.options[decision.index])
        // CR 609.4: the named card type, which then drives the reveal and its partition.
        is DecisionRequest.ChooseRevealedCardType ->
            applyChosenRevealType(state, request.options[decision.index])
        else -> error("no application for ${request::class.simpleName}; every answered request must have one")
    }

/**
 * Applies a chosen target (CR 601.2c), the single-target shape.
 */
private fun applyChosenTargets(
    state: GameState,
    request: DecisionRequest.ChooseTargets,
    decision: Decision.SingleSelect,
): AdvanceResult = applyChosenTargetList(state, listOf(request.options[decision.index]), request)

/**
 * Records [targets] on whichever flow is choosing them (CR 601.2c). One choice serves three flows — a
 * cast (CR 601.2c), an activation (CR 602.2b), and a triggered ability being put on the stack
 * (CR 603.3d) — and the open pending record says which.
 *
 * Shared by both target request kinds (`FW-MULTITGT`): [DecisionRequest.ChooseTargets] arrives here with
 * a one-element list and [DecisionRequest.ChooseMultipleTargets] with between its minimum and its
 * maximum. Routing them through one function is what keeps the two shapes from diverging on *where* a
 * chosen target is recorded — the only thing that legitimately differs between them is how the agent
 * expressed the choice. [request] appears only in the failure message.
 */
internal fun applyChosenTargetList(
    state: GameState,
    targets: List<Target>,
    request: DecisionRequest,
): AdvanceResult =
    when {
        state.pendingCast != null -> applyChosenTarget(state, targets)
        state.pendingActivation != null -> applyChosenActivationTarget(state, targets)
        state.pendingTriggerTargets != null -> applyChosenTriggerTarget(state, targets)
        else -> error("a target was chosen with no cast, activation, or trigger placement awaiting one: $request")
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
        // CR 702.49a / CR 602.2b: the ninjutsu ability's mana cost.
        state.pendingNinjutsu != null -> executeNinjutsu(state, plan)
        state.pendingActivation != null -> executeActivation(state, plan)
        else -> executeCastPipeline(state, plan)
    }
}

/**
 * Routes a CR 601.2b announcement of X to whichever record is open (`W9-C`): the cast in progress, or the
 * activation in progress.
 *
 * One request kind, two pipelines, and the open record is what tells them apart — exactly as
 * `DecisionRequest.ChooseTargets` has served a cast, an activation and a trigger placement since
 * `FW-ABILTGT`. The two cannot both be open: a cast and an activation each hold priority for their whole
 * gathering, so there is never an ambiguity to resolve.
 *
 * The **positions differ** — the cast path announces X last and the activation path first — but the
 * *answer* does not, which is why the request is shared rather than duplicated. `AbilityXCost.kt`'s header
 * argues the asymmetry.
 */
private fun applyAnnouncedX(
    state: GameState,
    value: Int,
): AdvanceResult =
    if (state.pendingActivation != null) {
        applyChosenAbilityX(state, value)
    } else {
        applyChosenXValue(state, value)
    }
