package dev.mtgplay.rules.engine

import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest

/*
 * Decision-shape validation (ADR-004): every incoming decision is checked against the pending request
 * before it is applied, so misuse — a wrong shape, an out-of-range index, a wrong-arity multi-select —
 * never silently corrupts replay (ADR-006). Split from PendingDecision.kt to keep each file small.
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
        // CR 514.1 / 601.2b/h / 602.2b: every fixed-size subset selection validates identically — a
        // distinct in-range subset of exactly the required size.
        is DecisionRequest.SizedSelection -> {
            validateDistinctSubset(request, decision, request.optionCount, "selection")
            val chosen = decision.asMultiSelect(request).indices.size
            require(chosen == request.requiredCount) {
                "exactly ${request.requiredCount} option(s) must be chosen, got $chosen"
            }
        }
        is DecisionRequest.DeclareAttackers -> {
            // CR 508.1: any subset of the eligible attackers is a legal declaration (the empty
            // subset included); the only cross-option rule is distinctness.
            validateDistinctSubset(request, decision, request.options.size, "attacker")
        }
        is DecisionRequest.DeclareBlockers -> validateBlockerDeclaration(request, decision)
        // CR 702.19e: the trample assignment is a single amount in 0..excess.
        is DecisionRequest.AssignTrampleDamage -> validateSingleSelect(request, decision, request.options.size)
        // CR 509.2 / 603.3b: an ordering answer permutes all of its options (blocker order, trigger order).
        is DecisionRequest.PermutationSelection ->
            validatePermutation(request, decision, request.permutationSize, "option", "CR 509.2/603.3b")
        // CR 702.35b: a yes/no is a single-select of exactly two options — decline (0) or accept (1).
        is DecisionRequest.ChooseYesNo ->
            validateSingleSelect(request, decision, DecisionRequest.ChooseYesNo.OPTION_COUNT)
        // CR 616.1: the affected player picks one applicable replacement to apply first.
        is DecisionRequest.ChooseReplacement -> validateSingleSelect(request, decision, request.options.size)
        // CR 614.12: an as-enters colour choice is a single-select of one of the offered colours.
        is DecisionRequest.ChooseColor -> validateSingleSelect(request, decision, request.options.size)
        // CR 701.16: a keep-one choice is a single-select of a revealed card or the "keep none" index.
        is DecisionRequest.ChooseFromRevealed -> validateSingleSelect(request, decision, request.choiceCount)
        // CR 103.4/103.5: the pre-game mulligan decisions share a validator (Mulligans.kt).
        is DecisionRequest.MulliganRequest -> validateMulliganDecision(request, decision)
    }
}

/** Validates a declare-blockers answer (CR 509.1): a distinct subset, no blocker chosen twice (CR 509.1a). */
private fun validateBlockerDeclaration(
    request: DecisionRequest.DeclareBlockers,
    decision: Decision,
) {
    validateDistinctSubset(request, decision, request.options.size, "block")
    val blockers = decision.asMultiSelect(request).indices.map { request.options[it].blocker }
    require(blockers.distinct().size == blockers.size) {
        "CR 509.1a: a creature blocks at most one attacker, but a blocker was chosen twice: $blockers"
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
internal fun validateDistinctSubset(
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
internal fun Decision.asMultiSelect(request: DecisionRequest): Decision.MultiSelect =
    this as? Decision.MultiSelect
        ?: error("unreachable: ${request::class.simpleName} decision shape was validated to MultiSelect")

internal fun validateSingleSelect(
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
