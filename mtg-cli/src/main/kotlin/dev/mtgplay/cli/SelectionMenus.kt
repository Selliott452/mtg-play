package dev.mtgplay.cli

import dev.mtgplay.core.definition.OptionalCostMode
import dev.mtgplay.rules.decision.DecisionRequest

/*
 * The menus for the grouped selection shapes (P6.4 deliverable 2): a fixed-size subset selection, a
 * full ordering, a "choose one or opt out" pick, and the pre-game mulligan decisions. Each `when`s
 * over its own small leaf set so a new leaf breaks compilation, and each renders card names rather
 * than bare indices (the corpus brief).
 */

/** A fixed-size subset selection (CR 514.1 / 601.2b/h / 602.2b): discard/exile/sacrifice N cards. */
internal fun sizedSelectionMenu(request: DecisionRequest.SizedSelection): List<String> {
    val (header, names) = sizedHeaderAndNames(request)
    return listOf(header) + numbered(names) + sizedHint(request.requiredCount)
}

/** The header line and per-option card names for a sized selection, by kind. */
private fun sizedHeaderAndNames(request: DecisionRequest.SizedSelection): Pair<String, List<String>> =
    when (request) {
        is DecisionRequest.ChooseDiscards ->
            "Discard exactly ${request.count} card(s) down to maximum hand size (CR 514.1):" to
                request.options.map { it.card.name }
        is DecisionRequest.ChooseCardsToExile ->
            "Exile exactly ${request.count} other card(s) to pay escape for ${request.card.name} (CR 601.2b):" to
                request.options.map { it.card.name }
        is DecisionRequest.ChooseSacrifices ->
            "Sacrifice exactly ${request.count} permanent(s) to pay for ${request.card.name} (CR 601.2h):" to
                request.options.map { it.card.name }
        is DecisionRequest.ChooseOptionalCostSacrifice ->
            "Sacrifice exactly ${request.count} permanent(s) to bargain ${request.card.name} (CR 601.2b):" to
                request.options.map { it.card.name }
        is DecisionRequest.ChooseTapsForCost ->
            "Tap exactly ${request.count} permanent(s) to pay for ${request.card.name} (CR 601.2h):" to
                request.options.map { it.card.name }
        is DecisionRequest.ChooseCardsToDiscardForCost ->
            "Discard exactly ${request.count} card(s) as a cost of ${request.card.name} (CR 601.2b):" to
                request.options.map { it.card.name }
        is DecisionRequest.ChooseSacrificesForCost ->
            "Sacrifice exactly ${request.count} permanent(s) as a cost of ${request.card.name} (CR 601.2b):" to
                request.options.map { it.card.name }
        else -> abilityAndResolutionHeaderAndNames(request)
    }

/**
 * The activation-side and mid-resolution arms of [sizedHeaderAndNames], split out to keep that `when`
 * inside detekt's complexity budget. The split is by *which stage* the selection belongs to — a cast's
 * cost above, an ability's cost or a resolution's own choice here — which is the same axis
 * `abilityCostSelectionToDto` uses on the wire side.
 */
private fun abilityAndResolutionHeaderAndNames(request: DecisionRequest.SizedSelection): Pair<String, List<String>> =
    when (request) {
        is DecisionRequest.ChooseAbilitySacrifice ->
            "Sacrifice exactly ${request.count} permanent(s) to pay ${request.card.name}'s ability (CR 602.1):" to
                request.options.map { it.card.name }
        is DecisionRequest.ChooseAbilityDiscard ->
            "Discard exactly ${request.count} card(s) to pay ${request.card.name}'s ability (CR 602.2b):" to
                request.options.map { it.card.name }
        is DecisionRequest.ChooseAbilityReturn ->
            "Return exactly ${request.count} permanent(s) to hand to pay ${request.card.name}'s ability " +
                "(CR 602.1):" to request.options.map { it.card.name }
        is DecisionRequest.ChooseOptionalDiscard ->
            "Discard exactly ${request.count} card(s) (CR 701.8):" to request.options.map { it.card.name }
        is DecisionRequest.ChooseOptionalCostObject ->
            "Choose one object to pay the optional cost (CR 601.3b):" to request.options.map { it.card.name }
        is DecisionRequest.ChooseResolutionDiscards ->
            "Discard exactly ${request.count} card(s) as the spell resolves (CR 601.2c):" to
                request.options.map { it.card.name }
        // CR 701.7a: the deciding seat is an *opponent* of the resolving object's controller, so the
        // header names the card making them discard — they did not choose to be here.
        is DecisionRequest.ChooseOpponentDiscards ->
            "Discard exactly ${request.count} card(s) to ${request.sourceCard.name} (CR 701.7a):" to
                request.options.map { it.card.name }
        // CR 701.17a: likewise an *opponent* of the controller, over their own battlefield. The header
        // says when the list has been narrowed, so a short list does not read as a missing option.
        is DecisionRequest.ChooseOpponentSacrifice ->
            "Sacrifice 1 permanent to ${request.sourceCard.name}" +
                (if (request.greatestPowerOnly) " (greatest power among yours, CR 701.17a):" else " (CR 701.17a):") to
                request.options.map { it.card.name }
        // Every cast-side cost is handled by [sizedHeaderAndNames]; reaching here would mean a leaf fell
        // out of both `when`s, which the compiler cannot catch once one of them carries an `else`.
        else -> error("CR 601.2b: no menu for the sized selection ${request::class.simpleName}")
    }

/**
 * A ranged subset selection: a multi-target choice (CR 601.2c) or an untargeted mid-resolution
 * permanent selection (CR 609.4). The header states the printed cardinality — "up to two", "two" —
 * because that is the part of the line neither an agent nor a human can infer from the option list, and
 * the hint states the bound actually enforced, which may be smaller when the board offers fewer options
 * than the card asks for.
 */
internal fun rangedSelectionMenu(
    view: MatchView,
    request: DecisionRequest.RangedSelection,
): List<String> =
    when (request) {
        // CR 601.2b: a mode choice is ranged too — "choose one" is the `1..1` case (`W9-B`).
        is DecisionRequest.ChooseModes -> modeMenu(request)
        is DecisionRequest.ChooseMultipleTargets ->
            listOf("Choose ${targetCountPhrase(request)} for ${request.card.name} (CR 601.2c):") +
                numbered(request.options.map { targetLabel(view, it) }) +
                rangedHint(request.minimumCount, request.maximumCount)
        // CR 609.4: the prompt carries the verb, because the option list alone cannot say whether the
        // chosen permanents will be untapped or bounced.
        is DecisionRequest.ChoosePermanentsToAffect ->
            listOf("${request.prompt} for ${request.sourceCard.name} (CR 609.4):") +
                numbered(request.options.map { it.card.name }) +
                rangedHint(request.minimumCount, request.maximumCount)
    }

/**
 * A summed-weight subset selection (CR 601.2b, CR 701.60a): collect evidence.
 *
 * Each option line carries its own mana value, because unlike every other selection menu the legality of
 * an answer here cannot be read off the numbers alone — a player who cannot see the weights cannot tell a
 * paying set from a failing one.
 */
internal fun summedSelectionMenu(request: DecisionRequest.SummedSelection): List<String> =
    when (request) {
        is DecisionRequest.ChooseEvidence ->
            listOf(
                "Exile cards totalling at least ${request.requiredTotal} mana value from your graveyard " +
                    "to collect evidence for ${request.card.name} (CR 701.60a):",
            ) +
                numbered(request.options.map { "${it.card.name} (mana value ${it.manaValue})" }) +
                summedHint(request.requiredTotal)
    }

/** How a multi-target request's cardinality reads in its header ("up to 2 target(s)"). */
private fun targetCountPhrase(request: DecisionRequest.ChooseMultipleTargets): String =
    if (request.minimumCount == 0) {
        "up to ${request.maximumCount} target(s)"
    } else {
        "${request.minimumCount} to ${request.maximumCount} target(s)"
    }

/** A full ordering (CR 509.2 blocker order / CR 603.3b trigger order). */
internal fun permutationMenu(
    view: MatchView,
    request: DecisionRequest.PermutationSelection,
): List<String> {
    val (header, labels) =
        when (request) {
            is DecisionRequest.OrderBlockers ->
                "Order ${combatantLabel(view, request.attacker, request.options.first().card)}'s blockers for " +
                    "damage assignment (CR 509.2):" to
                    request.options.map { combatantLabel(view, it.blocker, it.card) }
            is DecisionRequest.OrderTriggers ->
                "Order your simultaneous triggers - first chosen goes on the stack first, resolves last " +
                    "(CR 603.3b):" to
                    request.options.map { "${it.sourceCard.name}: ${it.description}" }
        }
    return listOf(header) + numbered(labels) + ORDER_HINT
}

/** A "choose one of these, or opt out" pick (CR 701.16 / 601.3b / 701.18): the opt-out is the last item. */
internal fun choiceCountMenu(request: DecisionRequest.ChoiceCountSelection): List<String> {
    val (header, labels, optOut) =
        when (request) {
            is DecisionRequest.ChooseFromRevealed ->
                Triple(
                    "Put one revealed card into your hand, or none (CR 701.16):",
                    request.options.map { it.card.name },
                    "keep none",
                )
            is DecisionRequest.ChooseFromLibrary ->
                Triple(
                    "Search your library: put one matching card into your hand, or none (CR 701.18):",
                    request.options.map { it.card.name },
                    "find none",
                )
            is DecisionRequest.ChooseCostMode ->
                Triple(
                    "${request.prompt} - choose an optional cost, or decline (CR 601.3b):",
                    request.options.map { optionalCostModeName(it) },
                    "decline",
                )
        }
    return listOf(header) + numbered(labels + "($optOut)") + SINGLE_HINT
}

/** The pre-game mulligan decisions (CR 103.4/103.5): keep-or-mulligan, or bottom cards after a keep. */
internal fun mulliganMenu(request: DecisionRequest.MulliganRequest): List<String> =
    when (request) {
        is DecisionRequest.ChooseMulligan ->
            listOf("Mulligan decision (CR 103.4) - ${request.mulligansTaken} taken so far:") +
                numbered(listOf("Keep this hand", "Mulligan (shuffle and redraw)")) +
                "  Enter 1/2; [Enter] = keep."
        is DecisionRequest.ChooseCardsToBottom ->
            listOf("Put exactly ${request.count} card(s) on the bottom of your library (CR 103.5):") +
                numbered(request.options.map { it.card.name }) +
                sizedHint(request.count)
    }

/** The name of an optional cost mode (CR 601.3b): discard a card, or sacrifice a land. */
private fun optionalCostModeName(mode: OptionalCostMode): String =
    when (mode) {
        OptionalCostMode.DiscardCard -> "discard a card"
        OptionalCostMode.SacrificeLand -> "sacrifice a land"
    }
