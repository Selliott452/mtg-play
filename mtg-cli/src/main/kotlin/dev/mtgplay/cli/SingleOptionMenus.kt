package dev.mtgplay.cli

import dev.mtgplay.rules.decision.DecisionRequest

/*
 * The menus of the "pick exactly one of these options" family
 * ([DecisionRequest.SingleOptionSelection]): a cast's target (CR 601.2c) and payment plan (CR 601.2g),
 * a trample assignment (CR 702.19e), an as-enters colour (CR 614.12), a replacement ordering
 * (CR 616.1), and a private look's arrangement (CR 701.17a). They share one branch in [renderMenu] and
 * one parse rule in [parseDecision] — a single one-based number with no opt-out index — but each still
 * renders its own header and labels, which is why they live together here rather than being collapsed
 * into a generic menu.
 */

/** The menu for one "pick exactly one of these options" request, rendered for [view]. */
internal fun singleOptionMenu(
    view: MatchView,
    request: DecisionRequest.SingleOptionSelection,
): List<String> =
    when (request) {
        is DecisionRequest.ChooseTargets -> targetMenu(view, request)
        is DecisionRequest.ChoosePaymentPlan -> paymentMenu(request)
        is DecisionRequest.ChooseXValue -> xValueMenu(request)
        is DecisionRequest.AssignTrampleDamage -> trampleMenu(view, request)
        is DecisionRequest.ChooseColor -> colorMenu(request)
        is DecisionRequest.ChooseReplacement -> replacementMenu(request)
        is DecisionRequest.ChooseLibraryArrangement -> libraryArrangementMenu(request)
        is DecisionRequest.ChooseCounterPayment -> counterPaymentMenu(request)
        is DecisionRequest.ChooseRevealedHandCard -> revealedHandMenu(request)
        else -> resolutionClauseMenu(view, request)
    }

/**
 * The controller's choice from an opponent's revealed hand (CR 701.16a) — Duress, Mesmeric Fiend. The
 * header names the revealer, because the deciding seat is choosing out of *somebody else's* hand and the
 * menu would otherwise read as a choice from its own.
 */
private fun revealedHandMenu(request: DecisionRequest.ChooseRevealedHandCard): List<String> =
    listOf(
        "Choose a card from seat ${request.revealer.seat}'s revealed hand " +
            "for ${request.sourceCard.name} (CR 701.16a):",
    ) + numbered(request.options.map { it.card.name }) + SINGLE_HINT

/**
 * A resolving counter's "unless its controller pays" choice (CR 118.3a). The header names the spell at
 * risk and the price, because the deciding seat here is the one being *asked*, not the one who cast the
 * counter, and would otherwise have no idea what the question is about.
 */
private fun counterPaymentMenu(request: DecisionRequest.ChooseCounterPayment): List<String> =
    listOf("Pay ${request.cost.render()} or ${request.card.name} is countered (CR 118.3a):") +
        numbered(
            request.options.map { option ->
                when (option) {
                    DecisionRequest.ChooseCounterPayment.Option.Decline ->
                        "Do not pay — ${request.card.name} is countered"
                    is DecisionRequest.ChooseCounterPayment.Option.Pay -> "Pay: ${paymentPlanLabel(option.plan)}"
                }
            },
        ) + SINGLE_HINT

/** A cast's target choice (CR 601.2c). */
private fun targetMenu(
    view: MatchView,
    request: DecisionRequest.ChooseTargets,
): List<String> =
    listOf("Choose a target for ${request.card.name} (CR 601.2c):") +
        numbered(request.options.map { targetLabel(view, it) }) +
        SINGLE_HINT

/**
 * A cast's payment choice (CR 601.2g); each plan shows its per-symbol assignments.
 *
 * The header names the **determined** cost (CR 601.2f) rather than the card's printed cost, because
 * since `FW-COST` they differ: an affinity Myr Enforcer printed at `{7}` reads "pay {3}" with four
 * artifacts out, and the plans below it pay three symbols. Rendering the printed cost here would make
 * every reduced cast look like a defect.
 */
private fun paymentMenu(request: DecisionRequest.ChoosePaymentPlan): List<String> =
    listOf("Choose how to pay ${request.cost.render()} for ${request.card.name} (CR 601.2g):") +
        numbered(request.options.map { paymentPlanLabel(it) }) +
        SINGLE_HINT

/** The trample damage-assignment choice (CR 702.19e): how much of the excess goes to the player. */
private fun trampleMenu(
    view: MatchView,
    request: DecisionRequest.AssignTrampleDamage,
): List<String> =
    listOf(
        "Assign ${request.attackerCard.name}'s trample damage to ${view.nameOf(request.defendingPlayer)} " +
            "(CR 702.19e):",
    ) + numbered(request.options.map { "$it to the player" }) + SINGLE_HINT

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

/**
 * A private library look's arrangement (CR 701.14a, CR 701.17a): the looked-at cards, then every legal
 * complete arrangement of them. Each option renders as its three destination groups in card names, so a
 * human reads "hand: Island | top: Ponder, Island | bottom: -" rather than three index lists.
 */
private fun libraryArrangementMenu(request: DecisionRequest.ChooseLibraryArrangement): List<String> {
    fun names(indices: List<Int>) =
        if (indices.isEmpty()) "-" else indices.joinToString(", ") { request.pool[it].card.name }
    val looked = request.pool.joinToString(", ") { it.card.name }.ifEmpty { "(nothing)" }
    return listOf("${request.prompt}. You looked at: $looked") +
        numbered(
            request.options.map { option ->
                "hand: ${names(option.toHand)} | top: ${names(option.toTop)} | bottom: ${names(option.toBottom)}"
            },
        ) + SINGLE_HINT
}
