package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetContext
import dev.mtgplay.core.definition.TargetCount
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import kotlinx.collections.immutable.toPersistentList

/*
 * A spell with **more than one instance of the word "target"** (CR 601.2c), and the dependence between
 * them. `W9-C`, docs/design/dependent-targets.md §2. Searing Blaze is the pool's only printing:
 *
 * > "Searing Blaze deals 1 damage to **target player** or planeswalker and 1 damage to **target
 * > creature that player** or that planeswalker's controller controls."
 *
 * `FW-MULTITGT` gave one targeting line a *count* and said, in `TargetCount`'s own KDoc and in
 * docs/design/multi-target.md §8, that a card printing two separate instances would need a **list** of
 * lines and was deliberately not modelled. This file is that list — and Searing Blaze needs more than the
 * list, because its second line is a function of its first.
 *
 * ## Two claims, and only the second is new
 *
 * **A list of lines** is the easy half. `SpellDefinition.additionalTargetSpecs` holds the lines after the
 * first, so a one-line card is the empty-list case and every existing card, request, replay log and wire
 * message is untouched. The flat `PendingCast.chosenTargets` / `StackEntry.Spell.targets` list is kept —
 * lines are recovered by *slicing* it ([targetsByLine]) rather than by nesting it — because the flat list
 * is what four other subsystems already read and re-shaping it would have rewritten all of them for a
 * card that does not need the nesting.
 *
 * **A dependent line** is the interesting half, and it is what forces the lines to be gathered **in
 * printed order**. "That player" is not a description of a board, it is a reference to the answer already
 * given, so line two's option set literally cannot be enumerated until line one is settled — the same
 * shape, from the other direction, that Gorilla Shaman's "with mana value X" has (`AbilityXCost.kt`). Both
 * arrive at the enumerator as a [TargetContext], which is why they are one mechanism rather than two.
 *
 * ## Why the slicing gate is exact counts, not a heuristic
 *
 * Recovering lines from a flat list needs the boundaries to be unambiguous, and it needs one more thing
 * besides: a way to tell a *finished* choice from a half-answered one. Both hold exactly when every line
 * of a multi-line card has a **fixed** count ([TargetCount.Exactly]). "Up to two target creatures and
 * target land" would leave a three-element record meaning either of two splits; "target player and up to
 * two target creatures" would leave a one-element record that is either finished or still owed a line.
 * [requireSliceableTargetLines] refuses both loudly. It costs the gauntlet nothing — Searing Blaze's two
 * lines are `Exactly(1)` each — and the fix, when a card demands it, is per-line boundaries on the record
 * rather than a cleverer slice.
 *
 * ## The castability gate, and why it is a search
 *
 * CR 601.2c: a spell whose targets cannot all be chosen cannot be cast. With independent lines that is a
 * conjunction of per-line tests; with a dependent line it is genuinely a **search** — Searing Blaze is
 * castable only when some player has a creature its caster may target, and asking the two lines
 * separately would answer "yes, there is a player" and "yes, there is a creature" on a board where the
 * creature belongs to neither offerable player. [targetLinesSatisfiable] therefore recurses over the
 * lines, extending the context as it goes, which is the same enumeration the gathering will perform. The
 * recursion is entered **only** for a multi-line card, so every other cast in the game pays nothing.
 */

/**
 * The targeting lines [definition] prints, in printed order (CR 601.2c) — the chosen mode's single line
 * for a modal card, otherwise [SpellDefinition.targetSpec] followed by
 * [SpellDefinition.additionalTargetSpecs].
 *
 * Always non-empty: a spell that targets nothing is the one-element list `[TargetSpec.None]`, whose count
 * is zero, so "how many lines?" and "does it target?" stay separate questions and no caller has to
 * special-case an empty list.
 */
internal fun targetLinesOf(
    definition: SpellDefinition,
    chosenModes: List<Int>,
): List<TargetSpec> {
    val first = effectiveTargetSpec(definition, chosenModes)
    if (definition.additionalTargetSpecs.isEmpty()) return listOf(first)
    require(definition.modes.isEmpty()) {
        "CR 601.2b/c: ${definition.characteristics.name} prints both modes and additional targeting " +
            "lines; a mode carries its own line, so the two together need a list of lines *per mode* " +
            "(`W9-C`, docs/design/dependent-targets.md §5)"
    }
    val lines = listOf(first) + definition.additionalTargetSpecs
    requireSliceableTargetLines(definition.characteristics.name, lines)
    return lines
}

/**
 * Fails loudly unless [lines] can be recovered from a flat target list (CR 601.2c) — every line of a
 * multi-line card demands a **fixed** number of targets.
 *
 * See the file header. A one-line card is trivially sliceable and never reaches the check. For a
 * multi-line card the flat record has to answer two questions — where does each line's slice begin, and
 * is the whole choice finished? — and a non-fixed count leaves both ambiguous: a three-element record for
 * "up to two target creatures and target land" could mean either split, and a two-element one for
 * "target player and up to two target creatures" could be finished or half-answered. Guessing either is
 * exactly the plausible-looking wrongness this codebase treats as worse than a crash, so the shape is
 * refused rather than approximated. The fix, the day a card prints it, is per-line boundaries on the
 * record — not a cleverer slice.
 */
internal fun requireSliceableTargetLines(
    name: String,
    lines: List<TargetSpec>,
) {
    if (lines.size <= 1) return
    lines.forEach { line ->
        require(line.count is TargetCount.Exactly) {
            "CR 601.2c: $name prints several instances of the word \"target\" and one of them demands " +
                "${line.count}; a flat target record can only be sliced back into lines when every line " +
                "has a fixed count (`W9-C`)"
        }
    }
}

/**
 * How many targets [line] demands, for the purpose of slicing a flat record (CR 601.2c) — its printed
 * count for a fixed line, and for the single line of a one-line card whatever the record happens to hold.
 */
private fun lineWidth(
    line: TargetSpec,
    remaining: Int,
): Int =
    when (val count = line.count) {
        is TargetCount.Exactly -> minOf(count.count, remaining)
        else -> remaining
    }

/**
 * [targets] split back into one list per line of [lines] (CR 601.2c), with empty lists for lines not yet
 * answered.
 *
 * Exact by construction for a multi-line card, whose every line has a fixed count
 * ([requireSliceableTargetLines]); for a one-line card the single line takes the whole record, which is
 * what every caller before this framework already assumed.
 */
internal fun targetsByLine(
    lines: List<TargetSpec>,
    targets: List<Target>,
): List<List<Target>> {
    var cursor = 0
    return lines.map { line ->
        val width = lineWidth(line, targets.size - cursor).coerceAtLeast(0)
        targets.subList(cursor, cursor + width).also { cursor += width }
    }
}

/**
 * The index of the first line of [lines] still owed a choice given the [targets] chosen so far, or `null`
 * when every line is settled (CR 601.2c).
 *
 * Meaningful only for a multi-line card, where every line's demand is fixed and "settled" is therefore a
 * count. A one-line card never consults it: for that card "answered" is exactly
 * `PendingCast.chosenTargets != null`, which is what it has always been and what keeps every existing
 * card's gathering byte-identical.
 */
internal fun firstUnsettledLine(
    lines: List<TargetSpec>,
    targets: List<Target>,
): Int? {
    var cursor = 0
    lines.forEachIndexed { index, line ->
        val required = line.count.maximum
        if (targets.size < cursor + required) return index
        cursor += required
    }
    return null
}

/**
 * Whether the CR 601.2c target choice of a spell printing [lines] is finished, given the record
 * [targets] — `null` meaning the first line has not been answered yet.
 *
 * The one-line case is unchanged from `FW-MULTITGT`: a non-null record *is* the answer, however many
 * targets it holds, which is what lets "up to two" settle at nought. The multi-line case counts, because
 * every one of its lines demands a fixed number.
 */
internal fun targetLinesSettled(
    lines: List<TargetSpec>,
    targets: List<Target>?,
): Boolean {
    val chosen = targets ?: return false
    return lines.size == 1 || firstUnsettledLine(lines, chosen) == null
}

/**
 * The [TargetContext] the [lineIndex]th line of [lines] is enumerated against, given the [targets] chosen
 * for the lines before it and the announced [chosenX] (CR 601.2b–c).
 *
 * The earlier lines' answers are handed over **flat and in order**, which is all any dependent restriction
 * needs today: "that player" reads whichever earlier target is a player. A restriction that had to
 * distinguish *which* earlier line an answer came from would take the sliced form instead, and the slice
 * is a line above.
 */
internal fun contextForLine(
    lines: List<TargetSpec>,
    targets: List<Target>,
    lineIndex: Int,
    chosenX: Int = 0,
): TargetContext =
    TargetContext(
        chosenX = chosenX,
        earlierTargets = targetsByLine(lines, targets).take(lineIndex).flatten().toPersistentList(),
    )

/**
 * Whether every line of [lines] can be given a legal choice (CR 601.2c) — the castability gate for a
 * spell that prints the word "target" more than once.
 *
 * A **search**, not a conjunction, and the file header says why: with a dependent line the answer depends
 * on *which* choice an earlier line makes, so the lines are walked in order and each candidate for a line
 * is tried against the lines below it. Depth-first, in enumeration order, returning as soon as one whole
 * assignment succeeds.
 *
 * **The cost is bounded by the shape of the pool rather than by an argument.** Only a multi-line card
 * reaches the recursion at all, and the one that does prints two lines whose first enumerates the players
 * — two candidates in a two-player game. A card printing three lines over the battlefield would want
 * memoising; none does, and adding it speculatively would obscure the one thing this has to get right.
 *
 * Note it tests **legal** targets rather than announceable ones. A CR 601.2c targeting *requirement*
 * (Standard Bearer) narrows what may be offered, and `announceableTargets` refuses a multi-target spell
 * outright while a requirement is in force — so consulting requirements here would fail loudly at a
 * legality gate, which is not a place that may throw.
 */
internal fun targetLinesSatisfiable(
    state: GameState,
    lines: List<TargetSpec>,
    seat: PlayerId,
    chooser: Chooser,
): Boolean = LineSearch(state, lines, seat, chooser).satisfiable(emptyList(), 0)

/**
 * The invariant half of [targetLinesSatisfiable]'s recursion — the board, the printed lines, the deciding
 * seat and the choosing object — bundled so the recursive step carries only what actually varies.
 */
private data class LineSearch(
    val state: GameState,
    val lines: List<TargetSpec>,
    val seat: PlayerId,
    val chooser: Chooser,
)

/**
 * Whether the lines from [lineIndex] onward can all be answered given [chosen], the answers to the lines
 * before it (CR 601.2c).
 *
 * A line that demands nothing is satisfied by choosing nothing, and there is no branch to explore: every
 * dependent restriction in the family reads earlier answers *additively*, so declining an optional line
 * can never make a later one more answerable than filling it would. The single-line case never recurses.
 */
private fun LineSearch.satisfiable(
    chosen: List<Target>,
    lineIndex: Int,
): Boolean {
    if (lineIndex > lines.lastIndex) return true
    val line = lines[lineIndex]
    val options = legalTargets(state, line, seat, chooser, contextForLine(lines, chosen, lineIndex))
    return when {
        options.size < line.count.minimum -> false
        line.count.minimum == 0 -> satisfiable(chosen, lineIndex + 1)
        else -> options.any { candidate -> satisfiable(chosen + candidate, lineIndex + 1) }
    }
}

/**
 * The CR 608.2b verdict for a spell with several targeting lines: whether **all** of its chosen targets
 * are now illegal, so it does not resolve and none of its instructions are performed.
 *
 * CR 608.2b is about the spell's targets as a whole, not per line — "if all its targets … are now
 * illegal" — so a spell whose creature has died but whose player is still there **resolves**, and deals
 * its damage to the player alone. That is the printed rules answer and it is the reason this is one
 * verdict over the sliced lines rather than a fold of per-line verdicts.
 *
 * Each line is re-checked against the context its *own* gathering used — the earlier lines' recorded
 * answers and the announced X, carried on [check] — which is what keeps the re-check asking the identical
 * question the choice answered. It reads the recorded earlier target even if that target has itself become
 * illegal, which is correct: "target creature that player controls" was chosen against the player named
 * then, and CR 608.2b asks whether the creature is still legal, not whether it would be chosen again.
 */
internal fun allTargetLinesIllegal(
    state: GameState,
    lines: List<TargetSpec>,
    targets: List<Target>,
    check: TargetCheck,
): Boolean {
    val byLine = targetsByLine(lines, targets)
    return lines.indices.all { index ->
        allTargetsIllegal(
            state,
            lines[index],
            byLine[index],
            check.copy(context = contextForLine(lines, targets, index, check.context.chosenX)),
        )
    }
}
