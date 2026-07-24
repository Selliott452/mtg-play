package dev.mtgplay.cli

import dev.mtgplay.core.state.StackEntry

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

/** One stack entry's label (CR 405.2): a spell (with targets) or a triggered/activated ability. */
private fun stackEntryLabel(
    view: MatchView,
    entry: StackEntry,
): String =
    when (entry) {
        is StackEntry.Spell -> {
            val targets =
                if (entry.targets.isEmpty()) {
                    ""
                } else {
                    " targeting ${entry.targets.joinToString(", ") { targetLabel(view, it) }}"
                }
            "${entry.obj.card.name} - cast by ${view.nameOf(entry.controller)}$targets"
        }
        is StackEntry.Ability ->
            "${entry.trigger.sourceCard.name} (triggered ability) - ${view.nameOf(entry.trigger.controller)}"
        is StackEntry.ActivatedAbilityOnStack ->
            "${entry.sourceCard.name} (activated ability) - ${view.nameOf(entry.controller)}"
    }

/** Turns an enum constant name into readable text, e.g. `PRECOMBAT_MAIN` -> `precombat main`. */
internal fun prettyName(name: String): String = name.lowercase().replace('_', ' ')
