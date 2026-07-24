package dev.mtgplay.cli

import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId

/*
 * Parsing a human's typed line into a [Decision] (P6.4 deliverable 2/3). Menu positions are
 * one-based, so every parsed number is converted to its engine index by subtracting one
 * ([MenuFormat]). A blank line is handled by the driver as "the safe default" ([defaultDecision])
 * before it reaches here; anything this parser cannot turn into a legal decision returns `null`, and
 * the driver re-prompts - the engine is never handed an out-of-range, wrong-arity, or duplicated
 * answer, so it never throws at the driver.
 */

/** Parses [input] against [request], or returns `null` if it is not a legal answer (re-prompt). */
fun parseDecision(
    request: DecisionRequest,
    input: String,
): Decision? {
    val trimmed = input.trim()
    return when (request) {
        is DecisionRequest.ChooseAction -> singleSelect(request.id, trimmed, request.options.size)
        is DecisionRequest.ChooseTargets -> singleSelect(request.id, trimmed, request.options.size)
        is DecisionRequest.ChoosePaymentPlan -> singleSelect(request.id, trimmed, request.options.size)
        is DecisionRequest.AssignTrampleDamage -> singleSelect(request.id, trimmed, request.options.size)
        is DecisionRequest.ChooseColor -> singleSelect(request.id, trimmed, request.options.size)
        is DecisionRequest.ChooseReplacement -> singleSelect(request.id, trimmed, request.options.size)
        is DecisionRequest.ChoiceCountSelection -> singleSelect(request.id, trimmed, request.choiceCount)
        is DecisionRequest.ChooseYesNo -> parseYesNo(request, trimmed)
        is DecisionRequest.DeclareAttackers -> parseSubset(request.id, trimmed, request.options.size, exactly = null)
        is DecisionRequest.DeclareBlockers -> parseBlockSubset(request, trimmed)
        is DecisionRequest.SizedSelection ->
            parseSubset(request.id, trimmed, request.optionCount, exactly = request.requiredCount)
        is DecisionRequest.PermutationSelection -> parsePermutation(request.id, trimmed, request.permutationSize)
        is DecisionRequest.MulliganRequest -> parseMulligan(request, trimmed)
    }
}

/** Parses one one-based number into a single-select over [count] options, or `null` if out of range. */
internal fun singleSelect(
    id: DecisionRequestId,
    input: String,
    count: Int,
): Decision.SingleSelect? {
    val index = (input.toIntOrNull() ?: return null) - 1
    return if (index in 0 until count) Decision.SingleSelect(id, index) else null
}

/**
 * Parses a comma-separated list of one-based numbers into a distinct in-range subset. [exactly], when
 * non-null, requires that many selections (a fixed-size cost); when null any size is legal (attackers).
 */
internal fun parseSubset(
    id: DecisionRequestId,
    input: String,
    optionCount: Int,
    exactly: Int?,
): Decision.MultiSelect? {
    val indices = parseIndices(input) ?: return null
    val inRange = indices.all { it in 0 until optionCount }
    val distinct = indices.size == indices.distinct().size
    val rightSize = exactly == null || indices.size == exactly
    return if (inRange && distinct && rightSize) Decision.MultiSelect(id, indices) else null
}

/** Parses a comma-separated list of one-based numbers into a permutation of all [size] options. */
internal fun parsePermutation(
    id: DecisionRequestId,
    input: String,
    size: Int,
): Decision.MultiSelect? {
    val indices = parseIndices(input) ?: return null
    return if (indices.sorted() == (0 until size).toList()) Decision.MultiSelect(id, indices) else null
}

/** Splits a comma-separated line into zero-based indices, or `null` if any token is not a number. */
internal fun parseIndices(input: String): List<Int>? {
    val parts = input.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val numbers = parts.mapNotNull { it.toIntOrNull() }
    if (parts.isEmpty() || numbers.size != parts.size) return null
    return numbers.map { it - 1 }
}
