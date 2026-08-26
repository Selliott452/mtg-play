package dev.mtgplay.cli

import dev.mtgplay.rules.decision.DecisionRequest

/*
 * The numbered decision menus (P6.4 deliverable 2). One exhaustive `when` over the whole
 * [DecisionRequest] hierarchy, so a future request kind breaks this file's compilation rather than
 * shipping a menu-less decision. The grouped selection shapes (sized/permutation/choice-count/
 * mulligan) render in [SelectionMenus] and the "pick exactly one option" family in
 * [singleOptionMenu]; the remaining direct kinds render here. Every menu ends with an input hint
 * documenting the syntax the parser expects.
 */

/** The menu lines (option list plus input hint) for [request], rendered for [view]. */
fun renderMenu(
    view: MatchView,
    request: DecisionRequest,
): List<String> =
    when (request) {
        is DecisionRequest.ChooseAction -> priorityMenu(request)
        is DecisionRequest.DeclareAttackers -> attackersMenu(view, request)
        is DecisionRequest.DeclareBlockers -> blockersMenu(view, request)
        is DecisionRequest.ChooseYesNo -> yesNoMenu(request)
        is DecisionRequest.SingleOptionSelection -> singleOptionMenu(view, request)
        is DecisionRequest.SizedSelection -> sizedSelectionMenu(request)
        is DecisionRequest.RangedSelection -> rangedSelectionMenu(view, request)
        is DecisionRequest.SummedSelection -> summedSelectionMenu(request)
        is DecisionRequest.PermutationSelection -> permutationMenu(view, request)
        is DecisionRequest.ChoiceCountSelection -> choiceCountMenu(request)
        is DecisionRequest.MulliganRequest -> mulliganMenu(request)
    }

/** A priority window (CR 117): pass, or take one of the enumerated actions; [Enter] passes. */
private fun priorityMenu(request: DecisionRequest.ChooseAction): List<String> =
    listOf("You have priority - choose an action (CR 117):") +
        numbered(request.options.map { priorityOptionLabel(it) }) +
        "  Enter one number; [Enter] = pass."

/**
 * The declare-attackers turn-based action (CR 508.1): any subset of the eligible attackers that
 * includes every creature CR 508.1d requires — a goaded one (CR 701.38a), marked here so the human
 * can see the rule their answer is held to rather than discovering it as a rejected line.
 */
private fun attackersMenu(
    view: MatchView,
    request: DecisionRequest.DeclareAttackers,
): List<String> {
    val required = request.requiredIndices.toSet()
    return listOf("Declare attackers (CR 508.1):") +
        numbered(
            request.options.mapIndexed { index, option ->
                val goaded = if (index in required) " [goaded - must attack]" else ""
                "${combatantLabel(view, option.attacker, option.card)} -> attacks " +
                    view.nameOf(option.defendingPlayer) + goaded
            },
        ) + SUBSET_HINT
}

/** The declare-blockers turn-based action (CR 509.1): each option pairs a blocker with an attacker. */
private fun blockersMenu(
    view: MatchView,
    request: DecisionRequest.DeclareBlockers,
): List<String> =
    listOf("Declare blocks (CR 509.1) - each number blocks one attacker with one creature:") +
        numbered(
            request.options.map {
                "${combatantLabel(view, it.blocker, it.blockerCard)} blocks " +
                    combatantLabel(view, it.attacker, it.attackerCard)
            },
        ) + SUBSET_HINT

/** A "you may" yes/no (CR 601.3b): decline is index 0, accept index 1 (menu positions 1 and 2). */
private fun yesNoMenu(request: DecisionRequest.ChooseYesNo): List<String> =
    listOf("${request.prompt} - concerning ${request.card.name}:") +
        numbered(listOf("No (decline)", "Yes")) +
        "  Enter 1/2 or n/y; [Enter] = No."
