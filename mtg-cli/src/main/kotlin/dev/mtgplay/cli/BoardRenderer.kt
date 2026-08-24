package dev.mtgplay.cli

import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target

/*
 * The per-seat board view (P6.4 deliverable 1): the turn header, both players' public board, the
 * viewer's hand, and the stack. Hidden-information discipline lives in [PlayerRenderer]; this file
 * assembles the sections and renders the stack.
 */

/** The full view for [view]'s current pause: turn header, both players, and the stack, as text lines. */
fun renderView(view: MatchView): List<String> =
    buildList {
        addAll(turnHeader(view))
        add("")
        addAll(renderPlayer(view, view.opponent, showHand = false))
        add("")
        addAll(renderPlayer(view, view.viewer, showHand = true))
        add("")
        addAll(stackLines(view))
    }

/** The turn banner (CR 500): turn number, active player, phase, and step (main phases have none). */
private fun turnHeader(view: MatchView): List<String> {
    val turn = view.state.turn
    val step = turn.step?.let { " / ${prettyName(it.name)}" } ?: ""
    return listOf(
        "================================================================",
        "Turn ${turn.number} - ${view.nameOf(turn.activePlayer)}'s turn - ${prettyName(turn.phase.name)}$step",
        "================================================================",
    )
}

/** The stack, top-first (CR 405.2, the last list element is the top), with each entry's controller. */
private fun stackLines(view: MatchView): List<String> {
    val stack = view.state.sharedZones.stack
    if (stack.isEmpty()) return listOf("Stack: (empty)")
    val entries =
        stack.reversed().mapIndexed { index, entry ->
            "  ${index + 1}. ${stackEntryLabel(view, entry)}"
        }
    return listOf("Stack (top resolves first):") + entries
}

/**
 * One stack entry's label (CR 405.2): a spell or a triggered/activated ability, each with its chosen
 * targets (CR 601.2c / 602.2b / 603.3d — all public).
 */
private fun stackEntryLabel(
    view: MatchView,
    entry: StackEntry,
): String =
    when (entry) {
        is StackEntry.Spell ->
            "${entry.obj.card.name} - cast by ${view.nameOf(entry.controller)}${targetSuffix(view, entry.targets)}"
        is StackEntry.Ability ->
            "${entry.trigger.sourceCard.name} (triggered ability) - " +
                "${view.nameOf(entry.trigger.controller)}${targetSuffix(view, entry.targets)}"
        is StackEntry.ActivatedAbilityOnStack ->
            "${entry.sourceCard.name} (activated ability) - " +
                "${view.nameOf(entry.controller)}${targetSuffix(view, entry.targets)}"
    }

/** The " targeting …" suffix of a stack entry, or the empty string when it targets nothing (CR 115.1). */
private fun targetSuffix(
    view: MatchView,
    targets: List<Target>,
): String =
    if (targets.isEmpty()) {
        ""
    } else {
        " targeting ${targets.joinToString(", ") { targetLabel(view, it) }}"
    }

/** Turns an enum constant name into readable text, e.g. `PRECOMBAT_MAIN` -> `precombat main`. */
internal fun prettyName(name: String): String = name.lowercase().replace('_', ' ')
