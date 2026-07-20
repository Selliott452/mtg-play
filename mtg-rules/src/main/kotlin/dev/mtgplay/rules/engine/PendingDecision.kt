package dev.mtgplay.rules.engine

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingCast
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.decision.Decision
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
 * The request the open [cast] is waiting on (CR 601.2): the target choice first (CR 601.2c),
 * then — always, even with a single plan (architect decision, P2.1: uniform decision
 * sequences keep replay logs canonical) — the payment choice (CR 601.2g). A pure function of
 * the state, like every pending request (ADR-004).
 */
internal fun pendingCastRequest(
    state: GameState,
    cast: PendingCast,
): DecisionRequest {
    val card =
        state.player(cast.caster).hand.firstOrNull { it.id == cast.cardObjectId }
            ?: error("CR 601.2: the pending cast's card ${cast.cardObjectId} is not in ${cast.caster}'s hand")
    val definition = spellDefinitionOf(state, card.card)
    val id = DecisionRequestId(cast.caster, state.player(cast.caster).decisionsAnswered)
    return if (cast.chosenTargets == null) {
        DecisionRequest.ChooseTargets(
            id = id,
            cardObjectId = cast.cardObjectId,
            card = card.card,
            options = legalTargets(state, definition.targetSpec),
        )
    } else {
        val cost =
            definition.manaCost
                ?: error(
                    "CR 601.2f: castable definition ${card.card.name} has no mana cost; alternative costs " +
                        "arrive in Phase 5 (docs/decklists.md)",
                )
        DecisionRequest.ChoosePaymentPlan(
            id = id,
            cardObjectId = cast.cardObjectId,
            card = card.card,
            options = enumeratePaymentPlans(state, cast.caster, cost),
        )
    }
}

/**
 * Recomputes the decision request [state] is paused at, or `null` if the state is not a pause
 * point. This is the resumability keystone (ADR-004): the pending request is a pure function of
 * the state, so `advance` validates any incoming decision against exactly what is pending.
 *
 * A pause is one of, checked in this order:
 * 1. a cast gathering decisions — [GameState.pendingCast] is open (CR 601.2); the caster also
 *    holds priority throughout the gathering, so this check must precede the window's;
 * 2. some player holds priority (a [DecisionRequest.ChooseAction] window, CR 117.1);
 * 3. the cleanup step's discard-to-hand-size is due — the active player's hand exceeds the
 *    maximum with no priority round open (CR 514.1).
 */
internal fun pendingDecisionRequest(state: GameState): DecisionRequest? {
    val holders =
        state.players
            .filterValues { it.priorityStatus == PriorityStatus.HOLDS_PRIORITY }
            .keys
            .toList()
    require(holders.size <= 1) { "CR 117: at most one player holds priority at a time, found $holders" }
    val cast = state.pendingCast
    val holder = holders.firstOrNull()
    return when {
        cast != null -> {
            require(holder == cast.caster) {
                "CR 601.2: the casting player ${cast.caster} must hold priority while gathering; holder was $holder"
            }
            pendingCastRequest(state, cast)
        }
        holder != null -> chooseActionRequest(state, holder)
        cleanupDiscardDue(state) -> cleanupDiscardRequest(state)
        else -> null
    }
}

/** Whether the cleanup step's discard down to maximum hand size is due (CR 514.1). */
private fun cleanupDiscardDue(state: GameState): Boolean =
    state.turn.step == TurnStep.CLEANUP &&
        state.player(state.turn.activePlayer).hand.size > MAXIMUM_HAND_SIZE

/**
 * Validates [decision] against the pending [request], failing loudly on any misuse (ADR-004):
 * answering a different request than the pending one, the wrong decision shape for the request
 * kind, an out-of-range index, or a wrong-arity or duplicated multi-select. Replay integrity
 * (ADR-006) depends on misuse never being silently tolerated.
 */
internal fun validateDecision(
    request: DecisionRequest,
    decision: Decision,
) {
    require(decision.requestId == request.id) {
        "decision answers request ${decision.requestId}, but the pending request is ${request.id}"
    }
    when (request) {
        is DecisionRequest.ChooseAction -> validateSingleSelect(request, decision, request.options.size)
        is DecisionRequest.ChooseTargets -> validateSingleSelect(request, decision, request.options.size)
        is DecisionRequest.ChoosePaymentPlan -> validateSingleSelect(request, decision, request.options.size)
        is DecisionRequest.ChooseDiscards -> {
            require(decision is Decision.MultiSelect) {
                "a ChooseDiscards request requires a MultiSelect decision, got ${decision::class.simpleName}"
            }
            require(decision.indices.size == request.count) {
                "CR 514.1: exactly ${request.count} discard(s) required, got ${decision.indices.size}"
            }
            require(decision.indices.distinct().size == decision.indices.size) {
                "discard indices must be distinct, got ${decision.indices}"
            }
            require(decision.indices.all { it in request.options.indices }) {
                "discard indices ${decision.indices} out of range for ${request.options.size} hand card(s)"
            }
        }
    }
}

private fun validateSingleSelect(
    request: DecisionRequest,
    decision: Decision,
    optionCount: Int,
) {
    require(decision is Decision.SingleSelect) {
        "a ${request::class.simpleName} request requires a SingleSelect decision, got ${decision::class.simpleName}"
    }
    require(decision.index in 0 until optionCount) {
        "option index ${decision.index} is out of range for $optionCount option(s)"
    }
}
