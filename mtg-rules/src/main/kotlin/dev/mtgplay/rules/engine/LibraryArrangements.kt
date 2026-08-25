package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.LibraryLook
import dev.mtgplay.core.definition.LibraryLookMode
import dev.mtgplay.rules.decision.DecisionRequest

/*
 * The enumeration of every legal arrangement of a looked-at pool (CR 701.14, CR 701.17), and its budget.
 * Split from LibraryLook.kt because this is the half with a mathematical contract — completeness,
 * injectivity, a closed-form count, and seed-independence — and it deserves to be readable on its own
 * (docs/design/library-look.md §4).
 *
 * ADR-005: legality is *defined by* this enumeration. A mandatory keep is expressed by not enumerating the
 * decline; a free partition is expressed by enumerating every split. Nothing downstream re-checks legality,
 * so anything missing here is unreachable and anything spurious here is a phantom option the fuzz harness's
 * EnumerationProbe will catch.
 *
 * ADR-006: the walk reads nothing but the pool size — no PRNG, no hash iteration, no identity tiebreak — so
 * the option list is a pure function of the pool and two calls on the same state are equal. Only Ponder's
 * separate "you may shuffle" consumes seeded entropy.
 */

/**
 * The largest arrangement space the engine will enumerate — `6!`, which admits every look up to five cards
 * in any mode (Ancient Stirrings, the deepest look in the gauntlet) and every hand-to-top placement from a
 * hand of up to 27. A clause that exceeds it fails loudly rather than shipping a truncated action space
 * (CONVENTIONS.md); the fix at that point is to decompose that clause into rounds, decided for that card
 * (docs/design/library-look.md §4.2).
 */
internal const val MAX_LIBRARY_ARRANGEMENTS: Int = 720

/**
 * Every legal arrangement of a pool of [poolSize] cards under [mode] (CR 701.14a, CR 701.17a), in the
 * deterministic order docs/design/library-look.md §4.3 fixes. Each option is a total assignment of
 * `0 until poolSize` across hand, top, and bottom.
 *
 * [matching] is the ascending list of pool indices that satisfy a *filtered* mode's keep predicate
 * ([LibraryLookMode.RevealMatchingToHandRestToBottom]); every other mode ignores it, because their keep is
 * unfiltered and so the pool's contents never change which arrangements are legal. It is an explicit
 * parameter rather than a defaulted one on purpose: a filtered mode enumerated against the wrong match set
 * is a *silently* wrong action space, which is the one failure ADR-005 cannot detect downstream.
 *
 * @throws IllegalStateException if the space exceeds [MAX_LIBRARY_ARRANGEMENTS].
 */
internal fun arrangementsFor(
    mode: LibraryLookMode,
    poolSize: Int,
    matching: List<Int>,
): List<DecisionRequest.ChooseLibraryArrangement.Option> {
    require(matching.all { it in 0 until poolSize } && matching == matching.sorted().distinct()) {
        "CR 701.14a: the matching indices must be distinct, ascending pool positions, was $matching " +
            "over a pool of $poolSize"
    }
    val budget = arrangementCount(mode, poolSize, matching.size)
    // Checked *before* enumerating, not after: the space is factorial, so a clause five cards past the
    // budget would exhaust the heap long before a count of the finished list could reject it.
    check(budget <= MAX_LIBRARY_ARRANGEMENTS) {
        "CR 701.17a: arranging $poolSize card(s) under $mode has $budget outcomes, over the engine's " +
            "budget of $MAX_LIBRARY_ARRANGEMENTS (docs/design/library-look.md §4.2)"
    }
    val indices = (0 until poolSize).toList()
    return when (mode) {
        // CR 701.17a: any number to the bottom in any order, the rest on top in any order. An outcome is
        // a permutation of the pool plus one divider, so there are exactly (poolSize + 1)! of them.
        is LibraryLookMode.Scry ->
            (0..poolSize).flatMap { split ->
                permutations(indices).map { order ->
                    arrangement(toBottom = order.take(split), toTop = order.drop(split))
                }
            }
        // CR 701.44a: any number into the graveyard, the rest on top in any order — scry's shape with a
        // public destination, so the same (poolSize + 1)! outcomes.
        is LibraryLookMode.Surveil ->
            (0..poolSize).flatMap { split ->
                permutations(indices).map { order ->
                    arrangement(toGraveyard = order.take(split), toTop = order.drop(split))
                }
            }
        // "Put them back in any order" (Ponder): every card returns to the top; poolSize! outcomes.
        is LibraryLookMode.ReorderTop -> permutations(indices).map { arrangement(toTop = it) }
        // "Put one of them into your hand and the rest on the bottom in any order" (Impulse). The keep is
        // mandatory, so no empty-hand arrangement is enumerated — unless the pool itself is empty, which
        // leaves nothing to keep (CR 701: do as much as possible).
        is LibraryLookMode.OneToHandRestToBottom ->
            indices
                .flatMap { kept ->
                    permutations(indices - kept).map { arrangement(toHand = listOf(kept), toBottom = it) }
                }.ifEmpty { listOf(arrangement()) }
        // "Put N cards from your hand on top of your library in any order" (Brainstorm): the ordered
        // placements, with every unplaced card staying in the hand in hand order so the option is total
        // and canonical — two options never differ only in an unobservable hand order.
        is LibraryLookMode.HandToTop ->
            orderedSelections(indices, minOf(mode.count, poolSize)).map { placed ->
                arrangement(toHand = indices - placed.toSet(), toTop = placed)
            }
        // "You may reveal <up to k> matching card(s) from among them and put them into your hand. Put the
        // rest on the bottom in any order" (Ancient Stirrings, Augur of Bolas, Lead the Stampede). The keep
        // is optional, so the empty subset is enumerated first and declining is index 0; it is filtered, so
        // only [matching] positions may be taken. Hand order is deliberately *not* a choice — the kept cards
        // are listed in pool order — because two options differing only in the order cards entered a hand
        // are indistinguishable to every rule in the game (the canonicalisation HandToTop already applies).
        is LibraryLookMode.RevealMatchingToHandRestToBottom -> filteredArrangements(mode, indices, matching)
    }
}

/**
 * How many arrangements [mode] admits over a pool of [poolSize] — the closed forms of
 * docs/design/library-look.md §4.2, computed without building the list so the budget can be enforced
 * before the factorial walk allocates anything. Saturates at [Long.MAX_VALUE] rather than overflowing, so
 * an absurd clause is rejected rather than wrapping into a small number.
 */
private fun arrangementCount(
    mode: LibraryLookMode,
    poolSize: Int,
    matchingCount: Int,
): Long =
    when (mode) {
        // (n + 1)!: a permutation of the pool plus one divider marking the bottom/top split.
        is LibraryLookMode.Scry -> factorial(poolSize + 1)
        // (n + 1)!: scry's count, with the graveyard where the bottom of the library was.
        is LibraryLookMode.Surveil -> factorial(poolSize + 1)
        is LibraryLookMode.ReorderTop -> factorial(poolSize)
        // n choices of kept card times (n - 1)! orders of the rest, which is n!; an empty pool keeps nothing.
        is LibraryLookMode.OneToHandRestToBottom -> factorial(poolSize)
        // The ordered k-selections of the hand: h! / (h - k)!, with k clamped to a short hand.
        is LibraryLookMode.HandToTop -> {
            val placed = minOf(mode.count, poolSize)
            (0 until placed).fold(1L) { total, step -> saturatingTimes(total, (poolSize - step).toLong()) }
        }
        is LibraryLookMode.RevealMatchingToHandRestToBottom ->
            filteredArrangementCount(mode, poolSize, matchingCount)
    }

/** `n!`, saturating at [Long.MAX_VALUE]; only ever compared against a budget three orders of magnitude below it. */
internal fun factorial(n: Int): Long = (2..n).fold(1L) { total, step -> saturatingTimes(total, step.toLong()) }

/** [left] * [right], clamped to [Long.MAX_VALUE] instead of wrapping. */
internal fun saturatingTimes(
    left: Long,
    right: Long,
): Long = if (right != 0L && left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right

/** A short human description of a look clause, for display beside its arrangements (ADR-005). */
internal fun arrangementPrompt(look: LibraryLook): String =
    when (val mode = look.mode) {
        is LibraryLookMode.Scry ->
            "Scry ${mode.count}: put any number on the bottom in any order, the rest on top in any order"
        is LibraryLookMode.Surveil ->
            "Surveil ${mode.count}: put any number into your graveyard, the rest on top in any order"
        is LibraryLookMode.ReorderTop ->
            "Put the ${mode.count} looked-at card(s) back on top of your library in any order"
        is LibraryLookMode.OneToHandRestToBottom ->
            "Put one looked-at card into your hand and the rest on the bottom of your library in any order"
        is LibraryLookMode.HandToTop ->
            "Put ${mode.count} card(s) from your hand on top of your library in any order"
        is LibraryLookMode.RevealMatchingToHandRestToBottom -> filteredArrangementPrompt(mode)
    }

/** One arrangement; the four lists together must cover the pool exactly once (CR 701.17a, CR 701.44a). */
internal fun arrangement(
    toHand: List<Int> = emptyList(),
    toTop: List<Int> = emptyList(),
    toBottom: List<Int> = emptyList(),
    toGraveyard: List<Int> = emptyList(),
) = DecisionRequest.ChooseLibraryArrangement.Option(
    toHand = toHand,
    toTop = toTop,
    toBottom = toBottom,
    toGraveyard = toGraveyard,
)

/**
 * The permutations of [items] in lexicographic order of their positions in [items] — the deterministic,
 * seed-independent order every ordering choice in this framework is enumerated in (ADR-005, ADR-006). The
 * empty list has exactly one permutation, the empty one, which is what makes an empty pool a real (if
 * forced) decision rather than a skipped one.
 */
internal fun permutations(items: List<Int>): List<List<Int>> =
    if (items.isEmpty()) {
        listOf(emptyList())
    } else {
        items.indices.flatMap { position ->
            val head = items[position]
            permutations(items.filterIndexed { index, _ -> index != position }).map { listOf(head) + it }
        }
    }

/**
 * The ordered selections of exactly [size] distinct entries of [items], in lexicographic order of their
 * positions — Brainstorm's "two cards from your hand **in any order**". `items.size! / (items.size - size)!`
 * of them; the empty selection when [size] is zero.
 */
private fun orderedSelections(
    items: List<Int>,
    size: Int,
): List<List<Int>> =
    if (size == 0) {
        listOf(emptyList())
    } else {
        items.indices.flatMap { position ->
            val head = items[position]
            orderedSelections(items.filterIndexed { index, _ -> index != position }, size - 1)
                .map { listOf(head) + it }
        }
    }
