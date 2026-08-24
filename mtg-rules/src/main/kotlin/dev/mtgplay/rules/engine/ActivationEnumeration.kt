package dev.mtgplay.rules.engine

import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.rules.decision.ManaActivation

/*
 * The activation half of payment enumeration (CR 601.2g), implementing
 * docs/design/mana-payment.md §§3.2–4: for a fixed per-symbol demand, every canonical multiset of
 * mana-ability activations that covers it and wastes none of its members.
 */

/**
 * One activation the search may pick: an activation of [activation]'s source class choosing its
 * [ManaActivation.produced] mana, together with everything that activation puts in the pool
 * ([produces] — [activationYield], the source's own mana plus its CR 605.1b triggered bonus) and
 * the index of its class, which the capacity check counts against.
 */
internal data class ActivationOption(
    val classIndex: Int,
    val activation: ManaActivation,
    val produces: List<ManaType>,
)

/**
 * Everything a payment plan may draw on: the caster's [pool] before the cast, the ordered
 * [options] one activation may take, each class's usable membership as [capacity], and the
 * caster's [life] (CR 118.8).
 *
 * @property obtainable every mana type any plan could supply — pooled or produced. A demanded
 *   type outside it is unpayable, so the payment search never offers one.
 * @property maxAvailable per [ManaType] ordinal, the most mana of that type the pool and a full
 *   use of every class could yield. A prefix whose demand exceeds it can never be completed, so
 *   the payment search prunes on it.
 */
internal class ManaSupply(
    val pool: Map<ManaType, Int>,
    val options: List<ActivationOption>,
    val capacity: List<Int>,
    val life: Int,
) {
    val obtainable: Set<ManaType> = pool.keys + options.flatMap { it.produces }

    val maxAvailable: IntArray =
        IntArray(ManaType.entries.size) { ordinal ->
            val type = ManaType.entries[ordinal]
            val fromClasses =
                capacity.indices.sumOf { classIndex ->
                    val best =
                        options
                            .filter { it.classIndex == classIndex }
                            .maxOfOrNull { option -> option.produces.count { it == type } }
                            ?: 0
                    capacity[classIndex] * best
                }
            (pool[type] ?: 0) + fromClasses
        }
}

/**
 * The [ManaSupply] for a caster with [pool] pooled mana, the given source [classes] in their
 * stable battlefield-class order, and [life] life. Options run class by class and, within a
 * class, over its profile's production alternatives in their canonical order (CR 105.1) — the
 * option order the activation multisets are sorted by.
 *
 * One option is one *alternative*, not one mana: an Urza's Tower with Tron assembled contributes a
 * single option that yields three colorless, not three options. That is why nothing in the search
 * below had to change for multi-mana production — the option list's length tracks the choices a
 * source offers, which is unrelated to how much each choice adds.
 */
internal fun manaSupply(
    pool: Map<ManaType, Int>,
    classes: List<SourceClass>,
    life: Int,
): ManaSupply {
    val options =
        classes.flatMapIndexed { classIndex, sourceClass ->
            sourceClass.key.profile.map { produced ->
                ActivationOption(
                    classIndex = classIndex,
                    activation = ManaActivation(sourceClass.key, produced),
                    produces = activationYield(sourceClass.key, produced),
                )
            }
        }
    return ManaSupply(pool, options, classes.map { it.members.size }, life)
}

/**
 * Every canonical activation multiset that legally backs a payment assignment whose per-type
 * [demand] is given (docs/design/mana-payment.md §§3.2, 4): sorted by option index — so each
 * distinct multiset is generated exactly once — bounded by class capacity, required to **cover**
 * the demand together with the pool, and required to waste no member ([everyActivationSpends]).
 * Emitted in lexicographic option-index order, the empty multiset first, which makes the plan
 * list total and seed-independent (ADR-006).
 *
 * Options that cannot produce a demanded type are dropped up front: no plan containing one could
 * satisfy the no-idle rule, so this prunes without removing a legal plan.
 */
internal fun enumerateActivationSets(
    supply: ManaSupply,
    demand: IntArray,
): List<List<ManaActivation>> {
    val demandMask = maskOf(ManaType.entries.filter { demand[it.ordinal] > 0 })
    val usable =
        supply.options
            .map { it to (maskOf(it.produces) and demandMask) }
            .filter { (_, mask) -> mask != 0 }
    val search = ActivationSearch(usable, supply, demand, maxSize = demand.sum())
    search.run(from = 0)
    return search.sets()
}

/**
 * Whether every activation spends at least one of the mana it produces — the bound of
 * docs/design/mana-payment.md §4, and the only legality clause with no CR counterpart.
 *
 * Each entry of [neighbourhoods] is the bitmask of demanded mana types one activation could pay
 * toward.
 * Because mana types never substitute for one another, "every activation spends something" is a
 * bipartite matching of activations to mana types with type capacities [demand], and its
 * deficiency form of Hall's theorem needs only the type subsets: for every subset `T`, the
 * activations whose neighbourhood lies wholly inside `T` must number no more than the demand for
 * `T`. Any violating activation set can be enlarged to one of that form, so the check is exact,
 * and it is a fixed sweep over the 64 subsets of the six mana types rather than a search.
 */
internal fun everyActivationSpends(
    neighbourhoods: List<Int>,
    demand: IntArray,
): Boolean {
    val typeCount = ManaType.entries.size
    for (subset in 0 until (1 shl typeCount)) {
        val confined = neighbourhoods.count { it and subset == it }
        if (confined == 0) continue
        var capacity = 0
        for (ordinal in 0 until typeCount) {
            if ((subset shr ordinal) and 1 == 1) capacity += demand[ordinal]
        }
        if (confined > capacity) return false
    }
    return true
}

/** The bitmask of [types] by [ManaType] ordinal. */
private fun maskOf(types: Collection<ManaType>): Int = types.fold(0) { mask, type -> mask or (1 shl type.ordinal) }

/**
 * The depth-first walk over [usable] activation options that collects the canonical multisets of
 * [enumerateActivationSets]: options are only ever extended at or after the last index taken, so
 * every sequence is non-decreasing and therefore the unique sorted representative of its multiset.
 * Each node — including the empty one — is tested and emitted before it is extended, which orders
 * the result lexicographically.
 */
private class ActivationSearch(
    private val usable: List<Pair<ActivationOption, Int>>,
    private val supply: ManaSupply,
    private val demand: IntArray,
    private val maxSize: Int,
) {
    private val chosen = mutableListOf<Pair<ActivationOption, Int>>()
    private val usedPerClass = IntArray(supply.capacity.size)
    private val found = mutableListOf<List<ManaActivation>>()

    fun run(from: Int) {
        collectIfLegal()
        if (chosen.size == maxSize) return
        for (index in from until usable.size) {
            val option = usable[index]
            val classIndex = option.first.classIndex
            if (usedPerClass[classIndex] >= supply.capacity[classIndex]) continue
            usedPerClass[classIndex] += 1
            chosen += option
            run(index)
            chosen.removeAt(chosen.lastIndex)
            usedPerClass[classIndex] -= 1
        }
    }

    fun sets(): List<List<ManaActivation>> = found.toList()

    private fun collectIfLegal() {
        if (!covers()) return
        if (!everyActivationSpends(chosen.map { it.second }, demand)) return
        found += chosen.map { it.first.activation }
    }

    /** Whether the pool plus the chosen activations' yields meet the demand, type by type. */
    private fun covers(): Boolean {
        val available = IntArray(ManaType.entries.size)
        supply.pool.forEach { (type, count) -> available[type.ordinal] += count }
        chosen.forEach { (option, _) -> option.produces.forEach { available[it.ordinal] += 1 } }
        return ManaType.entries.all { demand[it.ordinal] <= available[it.ordinal] }
    }
}
