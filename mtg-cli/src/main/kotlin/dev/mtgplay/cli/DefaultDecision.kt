package dev.mtgplay.cli

import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption

/*
 * The "safe default" answer for every request kind (P6.4): the passive, always-legal choice a blank
 * input takes - pressing Enter to pass. This is the ergonomic heart of the pass-heavy driver (the
 * P6.3 corpus averaged ~490 priority passes per game): a blank line never dead-ends because the
 * default is, by construction, always a legal decision.
 *
 * Defaults, by kind:
 *  - priority window (CR 117): pass;
 *  - target / payment / colour / replacement / trample: the first option;
 *  - attackers (CR 508): declare only what CR 508.1d requires - nothing, unless something is goaded;
 *  - blockers (CR 509): declare none;
 *  - fixed-size selection: the first N options (the lowest-index cards, as the pass-everything policy);
 *  - ranged (multi-target) selection: the fewest allowed — none for an "up to N" line, which is the
 *    passive answer, and the first N for a line that demands them;
 *  - full ordering: the identity order (0,1,2,...);
 *  - "choose one or opt out": opt out (keep/find none, decline), or the last option when the
 *    request is mandatory and offers no opt-out;
 *  - yes/no: decline;
 *  - mulligan: keep, and - if bottoming - the first N.
 */
fun defaultDecision(request: DecisionRequest): Decision =
    when (request) {
        is DecisionRequest.ChooseAction -> Decision.SingleSelect(request.id, passIndex(request))
        // Every "pick exactly one of these options" request defaults to its first option, which is always
        // legal (CR 601.2c/601.2g/702.19e/614.12/616.1/701.17a).
        is DecisionRequest.SingleOptionSelection -> Decision.SingleSelect(request.id, 0)
        is DecisionRequest.ChooseYesNo -> Decision.SingleSelect(request.id, DecisionRequest.ChooseYesNo.DECLINE)
        // CR 508.1: attacking with nothing is the passive choice — except that CR 508.1d's requirements
        // are not optional, so the default declaration is the goaded creatures and nothing else. A blank
        // line must still produce a *legal* answer (ADR-005), and the empty subset stops being one the
        // moment anything is goaded.
        is DecisionRequest.DeclareAttackers -> Decision.MultiSelect(request.id, request.requiredIndices)
        is DecisionRequest.DeclareBlockers -> Decision.MultiSelect(request.id, emptyList())
        is DecisionRequest.SizedSelection -> Decision.MultiSelect(request.id, (0 until request.requiredCount).toList())
        // CR 601.2c: the minimum is always legal and is the passive choice — an "up to N" target line
        // defaults to declining, matching this file's "blank input takes the do-nothing option" rule.
        is DecisionRequest.RangedSelection -> Decision.MultiSelect(request.id, (0 until request.minimumCount).toList())
        // CR 601.2b/701.60a: a summed selection has no "do nothing" answer — declining happened one
        // stage earlier, at the announcement — so the default is the cheapest legal payment, taking
        // options in order until the threshold is reached.
        is DecisionRequest.SummedSelection -> Decision.MultiSelect(request.id, cheapestPayment(request))
        is DecisionRequest.PermutationSelection ->
            Decision.MultiSelect(request.id, (0 until request.permutationSize).toList())
        // The last legal index (CR 701.16/601.3b/701.18): the trailing opt-out — keep/find none,
        // decline — where the request offers one, and the last real option on a *mandatory* reveal
        // (`W11`), which has no opt-out index and where declining is not a choice.
        is DecisionRequest.ChoiceCountSelection -> Decision.SingleSelect(request.id, request.choiceCount - 1)
        is DecisionRequest.MulliganRequest -> mulliganDefault(request)
    }

/**
 * The first legal payment of a summed selection (CR 601.2b, CR 701.60a): options in index order until
 * their weights reach the threshold. Always succeeds — the request is surfaced only when the whole
 * option list can pay it, so the loop cannot run out.
 */
private fun cheapestPayment(request: DecisionRequest.SummedSelection): List<Int> {
    var total = 0
    return (0 until request.optionCount).takeWhile { index ->
        val short = total < request.requiredTotal
        total += request.optionWeights[index]
        short
    }
}

/** The default mulligan answer: keep the hand, or - if bottoming - bottom the first [count] cards. */
private fun mulliganDefault(request: DecisionRequest.MulliganRequest): Decision =
    when (request) {
        is DecisionRequest.ChooseMulligan ->
            Decision.SingleSelect(request.id, DecisionRequest.ChooseMulligan.KEEP)
        is DecisionRequest.ChooseCardsToBottom ->
            Decision.MultiSelect(request.id, (0 until request.count).toList())
    }

/** The (always present) pass option's index in a priority window (CR 117.3d). */
internal fun passIndex(request: DecisionRequest.ChooseAction): Int =
    request.options
        .indexOfFirst { it is PriorityOption.Pass }
        .also { check(it >= 0) { "CR 117.3d: a priority window always enumerates pass" } }
