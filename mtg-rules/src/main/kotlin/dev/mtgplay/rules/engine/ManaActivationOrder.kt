package dev.mtgplay.rules.engine

import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.rules.decision.ManaActivation

/*
 * The execution **order** of a payment plan's CR 601.2g activations (docs/design/mana-payment.md
 * §11.2) — the acyclicity half of `FW-MANACOST`.
 *
 * Until a mana ability could cost mana, a plan's activations were order-free: every one of them only
 * *added* to the pool, so any sequence produced the same result and the plan could be a pure multiset
 * (§3.2). A costed activation both consumes and produces, and that makes order load-bearing:
 *
 *   Empty pool, two Giant's Boulders ("{1}, {T}: Add one mana of any color"). Aggregate arithmetic is
 *   happy — two mana produced, two mana of activation cost — but neither Boulder can go first, so the
 *   pair funds itself out of nothing. **This is the plan the enumerator must not offer.**
 *
 * The fix is *not* to record an order in the plan. Recording it would multiply every plan by its
 * permutations, and the §3.3 dedup argument ("two enumerated plans that differ as data differ in
 * outcome") would be false — the same multiset run in two feasible orders leaves the identical state.
 * The order is **derived** instead, by this file, from data the plan does record: the multiset, the
 * pool, and each activation's [ManaActivation.costPayment].
 *
 * **One derivation, two callers** — the discipline `P-MANASICK` established for [manaSourceUsable]
 * and `FW-MANA` for [sourceClassKeyOf], for the same reason. [manaActivationOrder] is what the
 * enumerator calls to decide whether a candidate multiset is feasible at all, and what the executor
 * calls to decide what to run first. A plan the enumerator accepted on one ordering rule and the
 * executor ran on another is precisely the enumerated-but-unexecutable defect ADR-005 forbids.
 */

/**
 * The largest number of *costed* activations one plan may hold. The order search is a subset DP over
 * them, so the bound is what keeps it a fixed cost rather than an open-ended one; no board can
 * approach it (a costed activation needs a mana source whose ability costs mana, and the gauntlet
 * pool prints four such cards, none of them run in multiples above four).
 */
private const val MAX_COSTED_ACTIVATIONS: Int = 12

/**
 * A feasible execution order for [activations] against [pool] (CR 601.2g), as indices into that list,
 * or `null` when no order is feasible — the plan funds itself out of nothing and must not be offered.
 *
 * The order is **canonical**: among all feasible orders it is the lexicographically first, so equal
 * states derive equal orders and a recorded plan index replays exactly (ADR-006). It is also
 * **complete**: it returns an order whenever one exists, which is what lets the enumerator use this
 * same function as its feasibility predicate without under-offering.
 *
 * Two structural facts make it cheap:
 *
 * 1. **Free activations run first, in plan order.** A free activation only ever adds to the pool (its
 *    tap, sacrifice, counter and tap-another-creature components touch the battlefield, never the
 *    pool), so moving one earlier can never make another activation unpayable. Ordering them is
 *    therefore unnecessary, and every plan on every board before `FW-MANACOST` is entirely free —
 *    which is why no existing board's execution order moved.
 * 2. **The pool after a set of costed activations depends only on the set**, not on the order within
 *    it: it is `base ⊎ Σ yields ⊖ Σ costs`. So feasibility is a subset property and the search is a
 *    DP over subsets rather than over permutations.
 */
internal fun manaActivationOrder(
    pool: Map<ManaType, Int>,
    activations: List<ManaActivation>,
): List<Int>? {
    val free = activations.indices.filter { activations[it].costPayment.isEmpty() }
    val costed = activations.indices.filter { activations[it].costPayment.isNotEmpty() }
    if (costed.isEmpty()) return free
    require(costed.size <= MAX_COSTED_ACTIVATIONS) {
        "CR 601.2g: ${costed.size} costed mana activations in one plan exceeds the order search's bound " +
            "of $MAX_COSTED_ACTIVATIONS; the payment model would need a different acyclicity argument"
    }
    val search = ActivationOrderSearch(basePool(pool, activations, free), activations, costed)
    return search.order()?.let { free + it }
}

/** The pool the costed activations start from: the caster's mana plus every free activation's yield. */
private fun basePool(
    pool: Map<ManaType, Int>,
    activations: List<ManaActivation>,
    free: List<Int>,
): IntArray {
    val counts = IntArray(ManaType.entries.size)
    pool.forEach { (type, count) -> counts[type.ordinal] += count }
    free.forEach { index ->
        activationYield(activations[index].sourceClass, activations[index].alternative)
            .forEach { counts[it.ordinal] += 1 }
    }
    return counts
}

/**
 * The subset DP of [manaActivationOrder] over the costed activations.
 *
 * `canFinish[remaining]` answers "can the activations still in `remaining` be run, in some order,
 * from the pool that executing all the *others* leaves?". It is filled bottom-up over subsets, and
 * the canonical order is then read off it greedily: at each step take the lowest-indexed remaining
 * activation whose cost the current pool covers **and** whose removal leaves a finishable remainder.
 * Greed without the second condition would be wrong — paying a `{1}` with the pool's only green can
 * strand a later activation that needed exactly that green — which is the trap a plain
 * "run whatever is payable" executor falls into.
 */
private class ActivationOrderSearch(
    private val base: IntArray,
    private val activations: List<ManaActivation>,
    private val costed: List<Int>,
) {
    private val types = ManaType.entries.size
    private val count = costed.size
    private val full = (1 shl count) - 1

    /** Per costed activation, the mana its own cost spends and the mana its ability yields. */
    private val spends = costed.map { counted(activations[it].costPayment) }
    private val yields =
        costed.map { counted(activationYield(activations[it].sourceClass, activations[it].alternative)) }

    private val canFinish = BooleanArray(1 shl count)

    fun order(): List<Int>? {
        fill()
        if (!canFinish[full]) return null
        val chosen = mutableListOf<Int>()
        var remaining = full
        while (remaining != 0) {
            val pool = poolAfter(full and remaining.inv())
            val next =
                (0 until count).firstOrNull { slot ->
                    val bit = 1 shl slot
                    bit and remaining != 0 && payable(pool, slot) && canFinish[remaining and bit.inv()]
                } ?: error("CR 601.2g: a finishable activation set has a first activation, by construction")
            chosen += costed[next]
            remaining = remaining and (1 shl next).inv()
        }
        return chosen
    }

    private fun fill() {
        canFinish[0] = true
        // Ascending mask order visits every proper subset of `remaining` before `remaining` itself,
        // because clearing a bit strictly decreases the mask.
        for (remaining in 1..full) {
            val pool = poolAfter(full and remaining.inv())
            canFinish[remaining] =
                (0 until count).any { slot ->
                    val bit = 1 shl slot
                    bit and remaining != 0 && payable(pool, slot) && canFinish[remaining and bit.inv()]
                }
        }
    }

    /** Whether [pool] covers the [slot]th costed activation's own mana cost, type by type (CR 601.2g). */
    private fun payable(
        pool: IntArray,
        slot: Int,
    ): Boolean = (0 until types).all { pool[it] >= spends[slot][it] }

    /**
     * The pool once every costed activation in [executed] has run: the base plus their yields minus
     * their costs. Order-free by construction, which is the property the DP rests on.
     */
    private fun poolAfter(executed: Int): IntArray {
        val pool = base.copyOf()
        for (slot in 0 until count) {
            if (executed and (1 shl slot) == 0) continue
            for (type in 0 until types) pool[type] += yields[slot][type] - spends[slot][type]
        }
        return pool
    }

    private fun counted(mana: List<ManaType>): IntArray {
        val counts = IntArray(types)
        mana.forEach { counts[it.ordinal] += 1 }
        return counts
    }
}
