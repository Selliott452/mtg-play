package dev.mtgplay.cli

import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.decision.DecisionRequest

/**
 * The menu of a resolving "you may tap or untap [target]" clause's three-way answer (CR 608.2c) —
 * Sewer-veillance Cam (`W8-G`). Its own file rather than a further member of SingleOptionMenus.kt,
 * which is at detekt's per-file function budget.
 *
 * The header names the **target**, because the three answers are otherwise indistinguishable from any
 * other three-option list and the deciding seat needs to see which creature they apply to. It names the
 * source from the request rather than looking it up on the battlefield: the Cam's second trigger fires
 * *because* the artifact left, so by the time this menu renders there may be nothing there to read
 * (CR 113.7c).
 */
internal fun tapOrUntapMenu(
    view: MatchView,
    request: DecisionRequest.ChooseTapOrUntap,
): List<String> {
    val target = targetLabel(view, Target.Permanent(request.targetId))
    return listOf("${request.card.name}: you may tap or untap $target (CR 608.2c):") +
        numbered(request.options.map { it.name.lowercase().replaceFirstChar(Char::uppercase) }) +
        SINGLE_HINT
}
