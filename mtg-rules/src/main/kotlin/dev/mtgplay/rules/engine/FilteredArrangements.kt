package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.LibraryLookMode
import dev.mtgplay.core.definition.RevealedCardFilter
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.DecisionRequest

/*
 * The one look mode whose legal arrangements depend on **what the pool holds** rather than only on how big
 * it is (CR 701.14a, CR 701.16a): [LibraryLookMode.RevealMatchingToHandRestToBottom] — Ancient Stirrings'
 * "You may reveal a colorless card from among them", Augur of Bolas' instant-or-sorcery, Lead the Stampede's
 * "any number of creature cards".
 *
 * Split out of LibraryArrangements.kt for the reason that file was split out of LibraryLook.kt: it is the
 * part with its own contract. Every other mode's enumeration is a pure walk over `0 until poolSize` and can
 * be read without knowing what a card is; this one needs a *predicate over printed characteristics*, which
 * is why the match set is computed here against the state ([matchingPoolIndices]) and handed to the walk as
 * plain indices — the walk itself stays a pure function of sizes and positions (ADR-005, ADR-006), so the
 * option list remains reproducible from the pool alone.
 *
 * The mode's two asymmetries against its unfiltered sibling [LibraryLookMode.OneToHandRestToBottom], both
 * straight off the oracle text: the keep is **optional** ("You *may* reveal"), so the empty-hand arrangement
 * is enumerated and is index 0; and it is **filtered**, so a non-matching card is never offered to the hand
 * at any index. Under ADR-005 those two sentences are the whole legality rule — nothing downstream re-checks
 * either one.
 */

/**
 * The ascending pool positions a **filtered** mode's keep may take (CR 701.16a): the cards in [pool] that
 * satisfy the mode's filter, read through the same [matchesFilter] the public reveal path uses. Empty for
 * every unfiltered mode, whose enumeration never reads the pool's contents at all.
 *
 * Exhaustive over [LibraryLookMode] rather than defaulting, so a future mode must state whether its keep is
 * filtered instead of silently inheriting "unfiltered" — the quiet outcome the no-`else`-branch rule exists
 * to prevent.
 */
internal fun matchingPoolIndices(
    state: GameState,
    mode: LibraryLookMode,
    pool: List<GameObject>,
): List<Int> =
    when (mode) {
        is LibraryLookMode.RevealMatchingToHandRestToBottom ->
            pool.indices.filter { matchesFilter(state, pool[it], mode.toHand) }
        is LibraryLookMode.Scry,
        is LibraryLookMode.Surveil,
        is LibraryLookMode.ReorderTop,
        is LibraryLookMode.OneToHandRestToBottom,
        is LibraryLookMode.HandToTop,
        -> emptyList()
    }

/**
 * Every legal arrangement of a filtered look over the pool positions [indices], whose matching members are
 * [matching] (CR 701.16a). An outcome is a subset of at most `mode.maxToHand` matching positions to the hand
 * plus an ordering of everything else to the bottom of the library; nothing ever goes on top.
 *
 * The kept cards are listed in **pool order** and are not permuted, which is the same canonicalisation
 * `HandToTop` applies to its residue: two options differing only in the order cards entered a hand are
 * indistinguishable to every rule in the game, so enumerating both would inflate the action space with a
 * distinction an agent could never learn to use.
 */
internal fun filteredArrangements(
    mode: LibraryLookMode.RevealMatchingToHandRestToBottom,
    indices: List<Int>,
    matching: List<Int>,
): List<DecisionRequest.ChooseLibraryArrangement.Option> =
    subsetsUpTo(matching, minOf(mode.maxToHand, matching.size)).flatMap { kept ->
        permutations(indices - kept.toSet()).map { rest ->
            arrangement(toHand = kept, toBottom = rest)
        }
    }

/**
 * How many arrangements a filtered look admits: the sum over the number `k` of matching cards kept, from
 * zero to `min(maxToHand, matchingCount)`, of `C(matchingCount, k) * (poolSize - k)!` — the `C(m, k)`
 * subsets of the matching positions times the orders of everything left over.
 *
 * The numbers this produces for the encoded cards, since they are the ones the 720 budget has to admit:
 * Ancient Stirrings at five deep with all five matching is `1 * 5! + 5 * 4! = 240`; Lead the Stampede at
 * five deep with all five matching is `120 + 120 + 60 + 20 + 5 + 1 = 326`, the widest single decision in the
 * encoded pool. Computed without building the list so the budget is enforced before the factorial walk
 * allocates anything, and saturating rather than wrapping so an absurd clause is rejected, not shrunk.
 */
internal fun filteredArrangementCount(
    mode: LibraryLookMode.RevealMatchingToHandRestToBottom,
    poolSize: Int,
    matchingCount: Int,
): Long =
    (0..minOf(mode.maxToHand, matchingCount)).fold(0L) { total, kept ->
        saturatingPlus(total, saturatingTimes(binomial(matchingCount, kept), factorial(poolSize - kept)))
    }

/** A short human description of a filtered look, for display beside its arrangements (ADR-005). */
internal fun filteredArrangementPrompt(mode: LibraryLookMode.RevealMatchingToHandRestToBottom): String {
    // "Any number" and "up to count" are the same enumeration (LibraryLookMode's KDoc says why); the prompt
    // is the one place the printed phrasing survives, so it is read back off that equality.
    val allowance = if (mode.maxToHand == mode.count) "any number of" else "up to ${mode.maxToHand}"
    return "You may reveal $allowance ${describeFilter(mode.toHand)} from among the ${mode.count} " +
        "looked-at card(s) and put them into your hand; put the rest on the bottom in any order"
}

/** The printed noun a [RevealedCardFilter] stands for, for the display prompt (ADR-005). */
internal fun describeFilter(filter: RevealedCardFilter): String =
    when (filter) {
        RevealedCardFilter.PERMANENT_CARD -> "permanent card(s)"
        RevealedCardFilter.ENCHANTMENT_CARD -> "enchantment card(s)"
        RevealedCardFilter.COLORLESS_CARD -> "colorless card(s)"
        RevealedCardFilter.INSTANT_OR_SORCERY_CARD -> "instant or sorcery card(s)"
        RevealedCardFilter.CREATURE_CARD -> "creature card(s)"
        RevealedCardFilter.LAND_CARD -> "land card(s)"
    }

/**
 * Every subset of [items] of size `0 .. maxSize`, ascending by size and, within a size, in lexicographic
 * order of position — the deterministic, seed-independent order this framework enumerates every choice in
 * (ADR-005, ADR-006). The empty subset comes first, so **declining an optional keep is index 0**, the
 * convention the rest of the engine already uses for a decline. Each subset is listed in ascending order, so
 * the kept cards enter the hand in pool order.
 */
private fun subsetsUpTo(
    items: List<Int>,
    maxSize: Int,
): List<List<Int>> = (0..maxSize).flatMap { size -> subsetsOfSize(items, size) }

/** The subsets of [items] of exactly [size], in lexicographic order of position, each ascending. */
private fun subsetsOfSize(
    items: List<Int>,
    size: Int,
): List<List<Int>> =
    if (size == 0) {
        listOf(emptyList())
    } else {
        items.indices.flatMap { position ->
            subsetsOfSize(items.drop(position + 1), size - 1).map { listOf(items[position]) + it }
        }
    }

/**
 * `C(n, k)`: how many ways [k] of [n] matching cards can be chosen, order disregarded. The running product
 * `C(n, i) * (n - i) / (i + 1)` is exact at every step because it *is* `C(n, i + 1)`, an integer. A pool wide
 * enough to overflow saturates instead — the caller compares against a budget three orders of magnitude
 * below [Long.MAX_VALUE], so a saturated count is rejected, never wrapped into a small one.
 */
private fun binomial(
    n: Int,
    k: Int,
): Long =
    when {
        k !in 0..n -> 0L
        n > BINOMIAL_EXACT_LIMIT -> Long.MAX_VALUE
        else -> (0 until minOf(k, n - k)).fold(1L) { total, step -> total * (n - step) / (step + 1) }
    }

/** [left] + [right], clamped to [Long.MAX_VALUE] instead of wrapping. */
private fun saturatingPlus(
    left: Long,
    right: Long,
): Long = if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

/** The widest pool `C(n, n / 2)` fits a [Long] for; beyond it [binomial] saturates rather than wrapping. */
private const val BINOMIAL_EXACT_LIMIT: Int = 62
