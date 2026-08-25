package dev.mtgplay.cli

import dev.mtgplay.rules.decision.DecisionRequest

/*
 * The CR 601.2b cost-announcement menus, split from SingleOptionMenus.kt so that file stays within its
 * function budget — the same split SelectionMenus.kt already is.
 */

/**
 * The CR 601.2b announcement of a variable cost (CR 107.3b). The menu lists the announceable *values*,
 * so the numbering and the values differ by one: rendering "1) X = 0" makes that visible rather than
 * inviting the reader to conflate an option index with the number it stands for. They would genuinely
 * diverge on a board where some middle value is unpayable.
 *
 * The header names the bound, because "these are the values you can afford" is the whole content of the
 * option set, and a player looking at a short list should be able to tell affordability from a defect.
 */
internal fun xValueMenu(request: DecisionRequest.ChooseXValue): List<String> =
    listOf(
        "Announce the value of X for ${request.card.name} " +
            "(CR 601.2b; only values you can pay for are offered):",
    ) + numbered(request.values.map { "X = $it" }) + SINGLE_HINT
