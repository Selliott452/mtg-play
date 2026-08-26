package dev.mtgplay.cli

import dev.mtgplay.rules.decision.DecisionRequest

/*
 * The mode menu (CR 601.2b, CR 700.2), in its own file because `W9-B` widened the mode decision past
 * arity one and `SelectionMenus.kt` was at detekt's function budget. The seam is real rather than
 * arithmetic: every other menu there renders a choice among *objects* — cards, permanents, targets —
 * and this one renders a choice among the card's own printed text.
 */

/**
 * A cast's mode choice (CR 601.2b, CR 700.2). The header names the count because the answer's shape
 * depends on it: "choose one" and "choose up to two" are the same request with different bounds, and a
 * player who cannot see the bounds cannot tell whether declining is on offer.
 */
internal fun modeMenu(request: DecisionRequest.ChooseModes): List<String> =
    listOf("Choose ${modeCountPhrase(request)} for ${request.card.name} (CR 601.2b):") +
        numbered(request.options.map { it.text }) +
        rangedHint(request.minimumCount, request.maximumCount)

/** How a mode request's cardinality reads in its header ("up to 2 mode(s)"). */
private fun modeCountPhrase(request: DecisionRequest.ChooseModes): String =
    when {
        request.minimumCount == 0 -> "up to ${request.maximumCount} mode(s)"
        request.minimumCount == request.maximumCount -> "${request.minimumCount} mode(s)"
        else -> "${request.minimumCount} to ${request.maximumCount} mode(s)"
    }
