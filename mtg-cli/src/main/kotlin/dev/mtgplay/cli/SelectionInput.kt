package dev.mtgplay.cli

import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest

/*
 * Parsers for the request kinds whose input is not a plain single-select or subset: the yes/no
 * (which also accepts y/n words), the block assignment (which must not use one blocker twice), and
 * the two mulligan decisions.
 */

/** Parses a yes/no answer (CR 601.3b): `y`/`yes`/`2` accept (index 1), `n`/`no`/`1` decline (index 0). */
internal fun parseYesNo(
    request: DecisionRequest.ChooseYesNo,
    input: String,
): Decision.SingleSelect? =
    when (input.lowercase()) {
        "y", "yes", "2" -> Decision.SingleSelect(request.id, DecisionRequest.ChooseYesNo.ACCEPT)
        "n", "no", "1" -> Decision.SingleSelect(request.id, DecisionRequest.ChooseYesNo.DECLINE)
        else -> null
    }

/**
 * Parses a block declaration (CR 509.1): a distinct, in-range subset of the (blocker, attacker)
 * options in which no blocker appears twice - a creature blocks at most one attacker in the MVP pool
 * (CR 509.1a). An answer that reuses a blocker is rejected, and the driver re-prompts.
 */
internal fun parseBlockSubset(
    request: DecisionRequest.DeclareBlockers,
    input: String,
): Decision.MultiSelect? {
    val indices = parseIndices(input) ?: return null
    val inRange = indices.all { it in request.options.indices }
    val distinctOptions = indices.size == indices.distinct().size
    // getOrNull keeps an out-of-range index from throwing here; inRange rejects it below.
    val blockers = indices.mapNotNull { request.options.getOrNull(it)?.blocker }
    val distinctBlockers = blockers.size == blockers.distinct().size
    val legal = inRange && distinctOptions && distinctBlockers
    return if (legal) Decision.MultiSelect(request.id, indices) else null
}

/** Parses a mulligan decision: keep/mulligan by number, or the bottom-cards fixed-size selection. */
internal fun parseMulligan(
    request: DecisionRequest.MulliganRequest,
    input: String,
): Decision? =
    when (request) {
        is DecisionRequest.ChooseMulligan ->
            singleSelect(request.id, input, DecisionRequest.ChooseMulligan.OPTION_COUNT)
        is DecisionRequest.ChooseCardsToBottom ->
            parseSubset(request.id, input, request.options.size, exactly = request.count)
    }
