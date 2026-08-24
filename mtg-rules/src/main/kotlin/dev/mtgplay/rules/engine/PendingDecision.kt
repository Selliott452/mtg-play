package dev.mtgplay.rules.engine

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId

/** The priority window for [seat], who holds priority in [state] (CR 117.1, ADR-005). */
internal fun chooseActionRequest(
    state: GameState,
    seat: PlayerId,
): DecisionRequest.ChooseAction =
    DecisionRequest.ChooseAction(
        id = DecisionRequestId(seat, state.player(seat).decisionsAnswered),
        options = legalPriorityOptions(state, seat),
    )

/**
 * The cleanup-step discard request (CR 402.2, CR 514.1) for [state]'s active player: one option
 * per hand card, requiring exactly hand size minus maximum hand size selections.
 */
internal fun cleanupDiscardRequest(state: GameState): DecisionRequest.ChooseDiscards {
    val seat = state.turn.activePlayer
    val hand = state.player(seat).hand
    return DecisionRequest.ChooseDiscards(
        id = DecisionRequestId(seat, state.player(seat).decisionsAnswered),
        options = hand.map { DecisionRequest.ChooseDiscards.Option(it.id, it.card) },
        count = hand.size - MAXIMUM_HAND_SIZE,
    )
}

/**
 * Recomputes the decision request [state] is paused at, or `null` if the state is not a pause
 * point. This is the resumability keystone (ADR-004): the pending request is a pure function of
 * the state, so `advance` validates any incoming decision against exactly what is pending.
 *
 * A terminal state short-circuits to `null` before any pause is derived (CR 104.2a): a state the
 * engine would rule game-over is not a pause point, and a finished game can carry a moot
 * fired-but-unplaced trigger that would otherwise mis-derive an [DecisionRequest.OrderTriggers]
 * request from a lone trigger and throw (CR 603.3b). Terminality is [isTerminalState], derived the
 * same way the state-based-action loop ends the game, so the two can never diverge.
 *
 * A pause is one of, checked in this order:
 * 0. the pre-game mulligan phase is running — [GameState.pendingMulligan] is set (CR 103.4/103.5);
 *    it precedes the whole game, so no player holds priority and the stack is empty here;
 * 1. a cast gathering decisions — [GameState.pendingCast] is open (CR 601.2); the caster also
 *    holds priority throughout the gathering, so this check must precede the window's;
 * 2. a triggered ability being put on the stack awaits its targets — [GameState.pendingTriggerTargets]
 *    is set (CR 603.3d) — or, failing that, simultaneous triggers await ordering —
 *    [GameState.pendingTriggers] is non-empty (CR 603.3b). Triggers are placed before any player
 *    receives priority, so no window is open here. The target check comes **first**: mid-batch, the
 *    controller's group may still hold two or more triggers whose order they have already chosen, and
 *    re-deriving an ordering request there would re-ask an answered question
 *    (docs/design/targeted-abilities.md §3.3);
 * 3. some player holds priority (a [DecisionRequest.ChooseAction] window, CR 117.1);
 * 4. a combat turn-based-action decision is due — declaring attackers/blockers or ordering
 *    blockers, all of which happen *before* the step grants priority (CR 508.1, CR 509.1–2), so
 *    they are only pending when no player holds priority (checked via [pendingCombatDecision]);
 * 5. the cleanup step's discard-to-hand-size is due — the active player's hand exceeds the
 *    maximum with no priority round open (CR 514.1).
 */
internal fun pendingDecisionRequest(state: GameState): DecisionRequest? {
    // CR 104.2a: a finished game is never a pause point, and short-circuiting here keeps a moot
    // dangling trigger from mis-deriving an ordering request downstream (see the KDoc).
    if (isTerminalState(state)) return null
    val holders =
        state.players
            .filterValues { it.priorityStatus == PriorityStatus.HOLDS_PRIORITY }
            .keys
            .toList()
    require(holders.size <= 1) { "CR 117: at most one player holds priority at a time, found $holders" }
    val holder = holders.firstOrNull()
    // Checked in order (see the KDoc): the pre-game mulligan phase; a priority-holding cost gathering; a
    // mid-transition pause; pending triggers; a priority window; a combat/cleanup turn-based action.
    return state.pendingMulligan?.let { pendingMulliganRequest(state, it) }
        ?: gatheringPauseRequest(state, holder)
        ?: midTransitionPauseRequest(state)
        ?: when {
            // CR 603.3d: a triggered ability chooses its targets as it is put on the stack — checked
            // before the ordering request, which is answered first and must not be re-asked mid-batch.
            state.pendingTriggerTargets != null -> pendingTriggerTargetsRequest(state)
            // CR 603.3b: pending triggers are ordered and placed before any priority window opens.
            state.pendingTriggers.isNotEmpty() -> pendingOrderTriggersRequest(state)
            holder != null -> chooseActionRequest(state, holder)
            else -> pendingCombatDecision(state) ?: if (cleanupDiscardDue(state)) cleanupDiscardRequest(state) else null
        }
}

/**
 * The request of a cost/payment gathering where the gathering player holds priority (CR 601.2 cast,
 * CR 702.140 plot, CR 602.2 activation), or `null` if none is open. Each asserts the gathering player is
 * the priority [holder], which is the resumability contract (ADR-004).
 */
private fun gatheringPauseRequest(
    state: GameState,
    holder: PlayerId?,
): DecisionRequest? {
    val cast = state.pendingCast
    val plot = state.pendingPlot
    val activation = state.pendingActivation
    return when {
        cast != null -> {
            require(holder == cast.caster) {
                "CR 601.2: the casting player ${cast.caster} must hold priority while gathering; holder was $holder"
            }
            pendingCastRequest(state, cast)
        }
        plot != null -> {
            require(holder == plot.caster) {
                "CR 702.140: the plotting player ${plot.caster} must hold priority while paying; holder was $holder"
            }
            pendingPlotRequest(state)
        }
        activation != null -> {
            require(holder == activation.activator) {
                "CR 602.2: the activating player ${activation.activator} must hold priority while paying; was $holder"
            }
            pendingActivationRequest(state)
        }
        else -> null
    }
}

/**
 * The request of a mid-transition pause with no priority round open — an as-enters colour choice
 * (CR 614.12), a reveal-keep-one (CR 701.16), a CR 616.1 replacement ordering, an optional
 * discard-then-draw (CR 601.3b), an optional cost-then-draw mode/object (CR 601.3b, Highway Robbery),
 * a mandatory resolution discard (CR 601.2c, Faithless Looting), a library search find-one (CR 701.18,
 * Ash Barrens), a private look's arrangement or its optional shuffle (CR 701.14a/701.17a, Preordain and
 * Ponder), or a madness yes/no (CR 702.35b) — or `null` if none is open.
 */
private fun midTransitionPauseRequest(state: GameState): DecisionRequest? =
    when {
        state.pendingColorChoice != null -> pendingColorChoiceRequest(state)
        state.pendingRevealSelection != null -> pendingRevealRequest(state)
        state.pendingReplacement != null -> pendingReplacementRequest(state)
        state.pendingOptionalDiscardDraw != null ->
            if (state.pendingOptionalDiscardDraw?.awaitingDiscard == true) {
                pendingOptionalDiscardSelectionRequest(state)
            } else {
                pendingOptionalDiscardYesNoRequest(state)
            }
        state.pendingOptionalCostDraw != null ->
            if (state.pendingOptionalCostDraw?.chosenMode == null) {
                pendingCostModeRequest(state)
            } else {
                pendingOptionalCostObjectRequest(state)
            }
        state.pendingResolutionDiscard != null -> pendingResolutionDiscardRequest(state)
        state.pendingLibrarySearch != null -> pendingLibrarySearchRequest(state)
        // CR 701.14a/701.17a: a private look, in either of its two stages — the arrangement, then the
        // clause's optional shuffle (Ponder). The stage is recorded on the pending look (ADR-004).
        state.pendingLibraryLook?.awaitingShuffle == true -> libraryLookShuffleRequest(state)
        state.pendingLibraryLook != null -> pendingLibraryLookRequest(state)
        state.pendingMadness != null -> pendingMadnessRequest(state)
        else -> null
    }

/** Whether the cleanup step's discard down to maximum hand size is due (CR 514.1). */
private fun cleanupDiscardDue(state: GameState): Boolean =
    state.turn.step == TurnStep.CLEANUP &&
        state.player(state.turn.activePlayer).hand.size > MAXIMUM_HAND_SIZE
