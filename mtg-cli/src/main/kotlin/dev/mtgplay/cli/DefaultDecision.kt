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
 *  - attackers / blockers (CR 508/509): declare none;
 *  - fixed-size selection: the first N options (the lowest-index cards, as the pass-everything policy);
 *  - full ordering: the identity order (0,1,2,...);
 *  - "choose one or opt out": opt out (keep/find none, decline);
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
        is DecisionRequest.DeclareAttackers -> Decision.MultiSelect(request.id, emptyList())
        is DecisionRequest.DeclareBlockers -> Decision.MultiSelect(request.id, emptyList())
        is DecisionRequest.SizedSelection -> Decision.MultiSelect(request.id, (0 until request.requiredCount).toList())
        is DecisionRequest.PermutationSelection ->
            Decision.MultiSelect(request.id, (0 until request.permutationSize).toList())
        // The trailing opt-out index is the last legal index (CR 701.16/601.3b/701.18): keep/find none, decline.
        is DecisionRequest.ChoiceCountSelection -> Decision.SingleSelect(request.id, request.choiceCount - 1)
        is DecisionRequest.MulliganRequest -> mulliganDefault(request)
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
