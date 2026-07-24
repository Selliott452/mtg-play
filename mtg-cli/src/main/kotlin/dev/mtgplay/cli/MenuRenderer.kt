package dev.mtgplay.cli

import dev.mtgplay.rules.decision.DecisionRequest

/*
 * The numbered decision menus (P6.4 deliverable 2). One exhaustive `when` over the whole
 * [DecisionRequest] hierarchy, so a future request kind breaks this file's compilation rather than
 * shipping a menu-less decision. The grouped selection shapes (sized/permutation/choice-count/
 * mulligan) render in [SelectionMenus]; the direct kinds render here. Every menu ends with an input
 * hint documenting the syntax the parser expects.
 */

/** The menu lines (option list plus input hint) for [request], rendered for [view]. */
fun renderMenu(
    view: MatchView,
    request: DecisionRequest,
): List<String> =
    when (request) {
        is DecisionRequest.ChooseAction -> priorityMenu(request)
        is DecisionRequest.ChooseTargets -> targetMenu(view, request)
        is DecisionRequest.ChoosePaymentPlan -> paymentMenu(request)
        is DecisionRequest.DeclareAttackers -> attackersMenu(view, request)
        is DecisionRequest.DeclareBlockers -> blockersMenu(view, request)
        is DecisionRequest.AssignTrampleDamage -> trampleMenu(view, request)
        is DecisionRequest.ChooseYesNo -> yesNoMenu(request)
        is DecisionRequest.ChooseColor -> colorMenu(request)
        is DecisionRequest.ChooseReplacement -> replacementMenu(request)
        is DecisionRequest.SizedSelection -> sizedSelectionMenu(request)
        is DecisionRequest.PermutationSelection -> permutationMenu(view, request)
        is DecisionRequest.ChoiceCountSelection -> choiceCountMenu(request)
        is DecisionRequest.MulliganRequest -> mulliganMenu(request)
    }

/** A priority window (CR 117): pass, or take one of the enumerated actions; [Enter] passes. */
private fun priorityMenu(request: DecisionRequest.ChooseAction): List<String> =
    listOf("You have priority - choose an action (CR 117):") +
        numbered(request.options.map { priorityOptionLabel(it) }) +
        "  Enter one number; [Enter] = pass."

/** A cast's target choice (CR 601.2c). */
private fun targetMenu(
    view: MatchView,
    request: DecisionRequest.ChooseTargets,
): List<String> =
    listOf("Choose a target for ${request.card.name} (CR 601.2c):") +
        numbered(request.options.map { targetLabel(view, it) }) +
        SINGLE_HINT

/** A cast's payment choice (CR 601.2g); each plan shows its per-symbol assignments. */
private fun paymentMenu(request: DecisionRequest.ChoosePaymentPlan): List<String> =
    listOf("Choose how to pay for ${request.card.name} (CR 601.2g):") +
        numbered(request.options.map { paymentPlanLabel(it) }) +
        SINGLE_HINT

/** The declare-attackers turn-based action (CR 508.1): any subset of the eligible attackers. */
private fun attackersMenu(
    view: MatchView,
    request: DecisionRequest.DeclareAttackers,
): List<String> =
    listOf("Declare attackers (CR 508.1):") +
        numbered(
            request.options.map {
                "${combatantLabel(view, it.attacker, it.card)} -> attacks ${view.nameOf(it.defendingPlayer)}"
            },
        ) + SUBSET_HINT

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

/** The trample damage-assignment choice (CR 702.19e): how much of the excess goes to the player. */
private fun trampleMenu(
    view: MatchView,
    request: DecisionRequest.AssignTrampleDamage,
): List<String> =
    listOf(
        "Assign ${request.attackerCard.name}'s trample damage to ${view.nameOf(request.defendingPlayer)} " +
            "(CR 702.19e):",
    ) + numbered(request.options.map { "$it to the player" }) + SINGLE_HINT

/** A "you may" yes/no (CR 601.3b): decline is index 0, accept index 1 (menu positions 1 and 2). */
private fun yesNoMenu(request: DecisionRequest.ChooseYesNo): List<String> =
    listOf("${request.prompt} - concerning ${request.card.name}:") +
        numbered(listOf("No (decline)", "Yes")) +
        "  Enter 1/2 or n/y; [Enter] = No."

/** An as-enters colour choice (CR 614.12). */
private fun colorMenu(request: DecisionRequest.ChooseColor): List<String> =
    listOf("Choose a colour as ${request.card.name} enters (CR 614.12):") +
        numbered(request.options.map { colorName(it) }) +
        SINGLE_HINT

/** A replacement-ordering choice (CR 616.1). */
private fun replacementMenu(request: DecisionRequest.ChooseReplacement): List<String> =
    listOf("Choose which replacement effect to apply first (CR 616.1):") +
        numbered(request.options.map { it.description }) +
        SINGLE_HINT
