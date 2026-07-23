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
        objectInZone(state, cast.caster, cast.source, cast.cardObjectId)
            ?: error("CR 601.2: pending cast's card ${cast.cardObjectId} is not in ${cast.caster}'s ${cast.source}")
    val definition = spellDefinitionOf(state, card.card)
    val id = DecisionRequestId(cast.caster, state.player(cast.caster).decisionsAnswered)

    // CR 601.2b/702.139a: every card in the source zone other than the one being cast is exilable.
    fun chooseCardsToExileRequest(): DecisionRequest.ChooseCardsToExile {
        val permission =
            cast.castingPermission ?: error("CR 601.2b: an additional exile cost requires a casting permission")
        return DecisionRequest.ChooseCardsToExile(
            id = id,
            cardObjectId = cast.cardObjectId,
            card = card.card,
            options =
                objectsInZone(state, cast.caster, cast.source)
                    .filter { it.id != cast.cardObjectId }
                    .map { DecisionRequest.ChooseCardsToExile.Option(it.id, it.card) },
            count = permission.additionalExileCount,
        )
    }

    return when {
        // CR 601.2c: targets first.
        cast.chosenTargets == null ->
            DecisionRequest.ChooseTargets(
                id = id,
                cardObjectId = cast.cardObjectId,
                card = card.card,
                options = legalTargets(state, definition.targetSpec, cast.caster),
            )
        // CR 601.2b: then any additional "exile N other cards" cost selection (escape).
        cast.additionalExileCost == null -> chooseCardsToExileRequest()
        // CR 601.2g: finally the payment plan for the (possibly alternative) mana cost.
        else -> {
            val cost =
                cast.castingPermission?.cost
                    ?: definition.manaCost
                    ?: error("CR 601.2f: ${card.card.name} has no mana cost and no alternative cost")
            DecisionRequest.ChoosePaymentPlan(
                id = id,
                cardObjectId = cast.cardObjectId,
                card = card.card,
                options = enumeratePaymentPlans(state, cast.caster, cost),
            )
        }
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
 * 2. simultaneous triggers await ordering — [GameState.pendingTriggers] is non-empty (CR 603.3b);
 *    triggers are put on the stack before any player receives priority, so no window is open here;
 * 3. some player holds priority (a [DecisionRequest.ChooseAction] window, CR 117.1);
 * 4. a combat turn-based-action decision is due — declaring attackers/blockers or ordering
 *    blockers, all of which happen *before* the step grants priority (CR 508.1, CR 509.1–2), so
 *    they are only pending when no player holds priority (checked via [pendingCombatDecision]);
 * 5. the cleanup step's discard-to-hand-size is due — the active player's hand exceeds the
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
        // CR 616.1: a discard with two or more replacements waits on the affected player's ordering,
        // mid-transition with no priority round open.
        state.pendingReplacement != null -> pendingReplacementRequest(state)
        // CR 702.35b: a resolved madness trigger waits on its owner's yes/no cast, also mid-transition.
        state.pendingMadness != null -> pendingMadnessRequest(state)
        // CR 603.3b: pending triggers are ordered and placed before any priority window opens.
        state.pendingTriggers.isNotEmpty() -> pendingOrderTriggersRequest(state)
        holder != null -> chooseActionRequest(state, holder)
        else -> pendingCombatDecision(state) ?: if (cleanupDiscardDue(state)) cleanupDiscardRequest(state) else null
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
            validateDistinctSubset(request, decision, request.options.size, "discard")
            val chosen = decision.asMultiSelect(request).indices.size
            require(chosen == request.count) { "CR 514.1: exactly ${request.count} discard(s) required, got $chosen" }
        }
        is DecisionRequest.DeclareAttackers -> {
            // CR 508.1: any subset of the eligible attackers is a legal declaration (the empty
            // subset included); the only cross-option rule is distinctness.
            validateDistinctSubset(request, decision, request.options.size, "attacker")
        }
        is DecisionRequest.DeclareBlockers -> {
            validateDistinctSubset(request, decision, request.options.size, "block")
            // CR 509.1a: a creature blocks at most one attacker, so no blocker may appear twice
            // across the chosen pairings.
            val blockers = decision.asMultiSelect(request).indices.map { request.options[it].blocker }
            require(blockers.distinct().size == blockers.size) {
                "CR 509.1a: a creature blocks at most one attacker, but a blocker was chosen twice: $blockers"
            }
        }
        // CR 509.2: the order is a permutation of exactly this attacker's blockers.
        is DecisionRequest.OrderBlockers ->
            validatePermutation(
                request,
                decision,
                request.options.size,
                "blocker",
                "CR 509.2",
            )
        // CR 603.3b: the order is a permutation of all of this controller's simultaneous triggers.
        is DecisionRequest.OrderTriggers ->
            validatePermutation(
                request,
                decision,
                request.options.size,
                "trigger",
                "CR 603.3b",
            )
        // CR 702.35b: a yes/no is a single-select of exactly two options — decline (0) or accept (1).
        is DecisionRequest.ChooseYesNo ->
            validateSingleSelect(request, decision, DecisionRequest.ChooseYesNo.OPTION_COUNT)
        is DecisionRequest.ChooseCardsToExile -> {
            validateDistinctSubset(request, decision, request.options.size, "exile")
            val chosen = decision.asMultiSelect(request).indices.size
            require(chosen == request.count) {
                "CR 601.2b: exactly ${request.count} card(s) must be exiled, got $chosen"
            }
        }
        // CR 616.1: the affected player picks one applicable replacement to apply first.
        is DecisionRequest.ChooseReplacement -> validateSingleSelect(request, decision, request.options.size)
    }
}

/**
 * Validates a multi-select answer as a permutation of all [optionCount] options — a full ordering with
 * the correct arity, no repeats, and every index in range (CR 509.2 blocker order, CR 603.3b trigger
 * order). [noun] and [cr] name the option kind and rule in the failure messages.
 */
private fun validatePermutation(
    request: DecisionRequest,
    decision: Decision,
    optionCount: Int,
    noun: String,
    cr: String,
) {
    require(decision is Decision.MultiSelect) {
        "a ${request::class.simpleName} request requires a MultiSelect decision, got ${decision::class.simpleName}"
    }
    require(decision.indices.size == optionCount) {
        "$cr: the order must permute all $optionCount ${noun}s, got ${decision.indices.size}"
    }
    require(decision.indices.distinct().size == decision.indices.size) {
        "$cr: a $noun order has no repeats, got ${decision.indices}"
    }
    require(decision.indices.all { it in 0 until optionCount }) {
        "$cr: order indices ${decision.indices} out of range for $optionCount $noun(s)"
    }
}

/**
 * Validates a multi-select answer as a distinct, in-range subset of [optionCount] options — of
 * any size, including empty (CR 508.1 / CR 509.1 both permit declaring nothing). [noun] names the
 * option kind in the failure message.
 */
private fun validateDistinctSubset(
    request: DecisionRequest,
    decision: Decision,
    optionCount: Int,
    noun: String,
) {
    require(decision is Decision.MultiSelect) {
        "a ${request::class.simpleName} request requires a MultiSelect decision, got ${decision::class.simpleName}"
    }
    require(decision.indices.distinct().size == decision.indices.size) {
        "$noun indices must be distinct, got ${decision.indices}"
    }
    require(decision.indices.all { it in 0 until optionCount }) {
        "$noun indices ${decision.indices} out of range for $optionCount option(s)"
    }
}

// The decision as a MultiSelect; only called after validateDistinctSubset has proven the shape.
private fun Decision.asMultiSelect(request: DecisionRequest): Decision.MultiSelect =
    this as? Decision.MultiSelect
        ?: error("unreachable: ${request::class.simpleName} decision shape was validated to MultiSelect")

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
