package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.ManaAbilityCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.rules.decision.ManaActivation

/*
 * The activation half of payment enumeration (CR 601.2g), implementing
 * docs/design/mana-payment.md §§3.2–4 and §11: for a fixed per-symbol demand, every canonical
 * multiset of mana-ability activations that covers it, can afford itself, fits the battlefield's
 * capacity, and wastes none of its members.
 *
 * **What `FW-MANACOST` changed here, and what it did not.** The search is still a walk over sorted
 * multisets of options, still bounded, still duplicate-free by the same argument. What moved is the
 * legality of a node, because an activation is no longer a pure producer:
 *
 * - **Coverage** now nets the activations' own mana costs out of the supply (§11.1).
 * - **Capacity** gained two more resources beside class membership: the pool, through those costs,
 *   and the seat's untapped creatures, through Saruli Caretaker's "Tap an untapped creature you
 *   control" (§11.3).
 * - **Acyclicity** is a new clause with no pre-`FW-MANACOST` counterpart: a set that can pay for
 *   itself in aggregate may still have no order in which to do it ([manaActivationOrder], §11.2).
 * - **No idle activation** could no longer be Hall's theorem over the demand alone, because an
 *   activation may legitimately spend its mana on *another* activation's cost. It is now an exact
 *   bipartite matching against demand-and-costs, with an activation forbidden from funding itself
 *   (§11.5) — and the old Hall check is kept, and still runs, on the boards where every activation is
 *   free, so no existing board pays for the generality.
 */

/**
 * One activation the search may pick: an activation of [activation]'s source class taking one
 * production alternative and one assignment of mana to that alternative's own cost, together with
 * everything the activation puts in the pool ([produces] — [activationYield], the source's own mana
 * plus its CR 605.1b triggered bonus), everything it takes out ([spends] — the recorded
 * [ManaActivation.costPayment]), and the index of its class, which the capacity check counts against.
 *
 * @property consumesSource whether activating this alternative taps or sacrifices its own member, and
 *   so removes that member from the seat's untapped creatures when the member is one.
 * @property tapsAnotherCreature whether this alternative's cost taps a *second* creature (CR 602.1) —
 *   Saruli Caretaker. The one place a class's activation consumes a resource that is not its own
 *   membership.
 */
internal data class ActivationOption(
    val classIndex: Int,
    val activation: ManaActivation,
    val produces: List<ManaType>,
    val spends: List<ManaType>,
    val consumesSource: Boolean,
    val tapsAnotherCreature: Boolean,
)

/**
 * Everything a payment plan may draw on: the caster's [pool] before the cast, the ordered [options]
 * one activation may take, each class's usable membership as [capacity], the caster's [life]
 * (CR 118.8), and — since `FW-MANACOST` — the seat's untapped creatures.
 *
 * @property untappedCreatures how many untapped creatures the seat controls (CR 602.1). The budget an
 *   [ManaAbilityCost.TapAnotherCreature] component and every creature source's own tap draw on.
 * @property creatureMembers per class, whether its `k`th member is an untapped creature. Members are
 *   consumed in battlefield order by both halves of payment ([resolveTapForMana] activates the first
 *   usable member, which the previous activation has just made unusable), so the `k`th *use* of a
 *   class is its `k`th member and this list indexes the drain exactly.
 * @property obtainable every mana type any plan could supply — pooled or produced. A demanded
 *   type outside it is unpayable, so the payment search never offers one.
 * @property maxAvailable per [ManaType] ordinal, the most mana of that type the pool and a full
 *   use of every class could yield. A prefix whose demand exceeds it can never be completed, so
 *   the payment search prunes on it. Activation costs are deliberately **not** netted out: leaving
 *   them in keeps this an upper bound, which is all a prune may be, and a tighter figure would risk
 *   deleting a legal plan rather than merely enumerating a doomed prefix.
 */
internal class ManaSupply(
    val pool: Map<ManaType, Int>,
    val options: List<ActivationOption>,
    val capacity: List<Int>,
    val life: Int,
    val untappedCreatures: Int = 0,
    val creatureMembers: List<List<Boolean>> = capacity.map { members -> List(members) { false } },
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

    /**
     * The most activations any legal plan for a [demandTotal]-unit demand can hold.
     *
     * Before `FW-MANACOST` this was exactly `demandTotal`, and the no-idle rule was the proof: every
     * activation claims a distinct unit of demand, so there cannot be more activations than demanded
     * mana (§4). A costed activation may instead claim a unit of *another activation's cost*, so the
     * sink count grows by every cost unit the battlefield could possibly present — bounded by class
     * capacity, which is finite and small. On a board with no costed mana ability the second term is
     * zero and the bound is the old one, unchanged.
     */
    fun activationBound(demandTotal: Int): Int {
        val costUnits =
            capacity.indices.sumOf { classIndex ->
                val widest =
                    options
                        .filter { it.classIndex == classIndex }
                        .maxOfOrNull { it.spends.size }
                        ?: 0
                capacity[classIndex] * widest
            }
        return minOf(demandTotal + costUnits, capacity.sum())
    }
}

/**
 * The [ManaSupply] for a caster with [pool] pooled mana, the given source [classes] in their
 * stable battlefield-class order, [life] life and [untappedCreatures] untapped creatures. Options run
 * class by class and, within a class, over its profile's production alternatives in their canonical
 * order (CR 105.1) and then over the assignments of mana to each alternative's own cost — the option
 * order the activation multisets are sorted by.
 *
 * One option is one *(alternative, cost assignment)* pair, not one mana: an Urza's Tower with Tron
 * assembled contributes a single option that yields three colorless, and a Giant's Boulder
 * contributes one option per (colour it may add × mana type its `{1}` is paid with). That is why
 * nothing in the search below had to change for multi-mana production, and why a costed ability
 * widens the option list rather than the search.
 */
internal fun manaSupply(
    pool: Map<ManaType, Int>,
    classes: List<SourceClass>,
    life: Int,
    untappedCreatures: Int = 0,
): ManaSupply {
    val obtainable =
        pool.keys +
            classes.flatMap { sourceClass ->
                sourceClass.key.profile.flatMap { it.produced + sourceClass.key.bonus }
            }
    val options =
        classes.flatMapIndexed { classIndex, sourceClass ->
            sourceClass.key.profile.flatMap { alternative ->
                costPaymentAssignments(alternative, obtainable).map { costPayment ->
                    ActivationOption(
                        classIndex = classIndex,
                        activation = ManaActivation(sourceClass.key, alternative, costPayment),
                        produces = activationYield(sourceClass.key, alternative),
                        spends = costPayment,
                        consumesSource =
                            ManaAbilityCost.TapSelf in alternative.cost ||
                                ManaAbilityCost.SacrificeSelf in alternative.cost,
                        tapsAnotherCreature = ManaAbilityCost.TapAnotherCreature in alternative.cost,
                    )
                }
            }
        }
    return ManaSupply(
        pool = pool,
        options = options,
        capacity = classes.map { it.members.size },
        life = life,
        untappedCreatures = untappedCreatures,
        creatureMembers = classes.map { it.untappedCreatureMembers },
    )
}

/**
 * Every canonical assignment of mana to [alternative]'s own activation cost (CR 601.2g), restricted to
 * the types some source could actually supply — one entry per expanded cost symbol, in printed order.
 * A single empty assignment for a free alternative, which is every alternative on every board before
 * `FW-MANACOST`.
 *
 * The assignments are the same combinations-with-repetition the outer payment search generates for the
 * *cost being paid* (§3.1), by the same non-decreasing rule within runs of identical symbols and for
 * the same reason: legality is a predicate on the whole plan and is invariant under permuting a run,
 * so the sorted representative may be chosen with no loss.
 */
private fun costPaymentAssignments(
    alternative: dev.mtgplay.rules.decision.ProductionAlternative,
    obtainable: Set<ManaType>,
): List<List<ManaType>> {
    val mana = alternative.manaCost ?: return listOf(emptyList())
    val units = expandToUnits(mana.cost)
    val candidates =
        units.map { symbol ->
            payableTypes(symbol).sortedBy(ManaType::ordinal).filter { it in obtainable }
        }
    var assignments: List<List<ManaType>> = listOf(emptyList())
    units.indices.forEach { index ->
        val startsRun = index == 0 || units[index] != units[index - 1]
        assignments =
            assignments.flatMap { prefix ->
                val floor = if (startsRun) 0 else candidates[index].indexOf(prefix.last())
                candidates[index].drop(maxOf(floor, 0)).map { prefix + it }
            }
    }
    return assignments
}

/**
 * Every canonical activation multiset that legally backs a payment assignment whose per-type
 * [demand] is given (docs/design/mana-payment.md §§3.2, 4, 11): sorted by option index — so each
 * distinct multiset is generated exactly once — bounded by class capacity and by the seat's untapped
 * creatures, required to **cover** the demand *and its own activation costs* together with the pool,
 * required to admit an execution order at all ([manaActivationOrder]), and required to waste no
 * member. Emitted in lexicographic option-index order, the empty multiset first, which makes the plan
 * list total and seed-independent (ADR-006).
 *
 * Options that can pay toward neither the demand nor any activation cost are dropped up front: no
 * plan containing one could satisfy the no-idle rule, so this prunes without removing a legal plan.
 * On a board with no costed mana ability the sink set is the demand alone, exactly as before.
 */
internal fun enumerateActivationSets(
    supply: ManaSupply,
    demand: IntArray,
): List<List<ManaActivation>> {
    val sinkMask =
        maskOf(ManaType.entries.filter { demand[it.ordinal] > 0 }) or
            supply.options.fold(0) { mask, option -> mask or maskOf(option.spends) }
    // The neighbourhood the free-path Hall check needs is the demand alone; the wider sink mask above
    // only decides which options are worth walking at all.
    val usable =
        supply.options
            .map { it to (maskOf(it.produces) and sinkMask) }
            .filter { (_, mask) -> mask != 0 }
    val demandMask = maskOf(ManaType.entries.filter { demand[it.ordinal] > 0 })
    val search =
        ActivationSearch(usable, supply, demand, demandMask, maxSize = supply.activationBound(demand.sum()))
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
 *
 * **This form is exact only while every activation is free.** With costed activations the sinks are
 * no longer interchangeable — an activation may fund another's cost but never its own — and Hall's
 * condition over type subsets stops being sufficient, so [everyActivationSpendsWithCosts] takes over.
 * The two agree on every board with no costed mana ability, which is why this one still runs there:
 * it is the cheaper check and it is the one the pinned budget measurements were taken against.
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

/**
 * The no-idle rule when activations cost mana (docs/design/mana-payment.md §11.5): every activation
 * must have at least one of its yielded mana spent on the cost being paid or on **another**
 * activation's activation cost.
 *
 * The self-exclusion is the whole point. Barrels of Blasting Jelly reads "{1}: Add one mana of any
 * color", so an activation of it that adds green and pays its own `{1}` with that same green is a
 * closed loop that changes nothing about the pool; without the exclusion Hall's condition would count
 * it as gainfully employed and the action space would fill with such no-ops, one per colour, on every
 * cast. (The loop is also unpayable in practice — CR 601.2g pays the cost *before* the ability adds
 * its mana, so the green has to come from somewhere else — but the *plan* would still enumerate,
 * paying the `{1}` with a pooled green and replacing it with an identical one.)
 *
 * A per-pair exclusion is exactly what Hall's condition over type subsets cannot express, so this is
 * a real bipartite matching: activations on the left, one node per unit of demand and per unit of each
 * activation's cost on the right, an edge when the activation yields that type and does not own that
 * unit. Kuhn's augmenting-path algorithm decides it; both sides are bounded by the plan's activation
 * count, so the whole check is a handful of nodes.
 */
internal fun everyActivationSpendsWithCosts(
    yields: List<List<ManaType>>,
    spends: List<List<ManaType>>,
    demand: IntArray,
): Boolean {
    val sinks = mutableListOf<Pair<Int, ManaType>>()
    ManaType.entries.forEach { type -> repeat(demand[type.ordinal]) { sinks += DEMAND_OWNER to type } }
    spends.forEachIndexed { owner, mana -> mana.forEach { sinks += owner to it } }
    val matchedTo = IntArray(sinks.size) { UNMATCHED }
    return yields.indices.all { activation ->
        augment(activation, yields, sinks, matchedTo, BooleanArray(sinks.size))
    }
}

/** The sink owner standing for the cost being paid, which no activation is forbidden from funding. */
private const val DEMAND_OWNER: Int = -1

/** The "no activation holds this sink unit yet" marker of the matching. */
private const val UNMATCHED: Int = -1

/** One augmenting-path step of Kuhn's algorithm for [everyActivationSpendsWithCosts]. */
private fun augment(
    activation: Int,
    yields: List<List<ManaType>>,
    sinks: List<Pair<Int, ManaType>>,
    matchedTo: IntArray,
    visited: BooleanArray,
): Boolean {
    sinks.forEachIndexed { index, (owner, type) ->
        // CR-free policy clause: an activation may fund the cost being paid or another activation's
        // cost, never its own — see the KDoc above for why that exclusion is load-bearing.
        if (visited[index] || owner == activation || type !in yields[activation]) return@forEachIndexed
        visited[index] = true
        if (matchedTo[index] == UNMATCHED || augment(matchedTo[index], yields, sinks, matchedTo, visited)) {
            matchedTo[index] = activation
            return true
        }
    }
    return false
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
    private val demandMask: Int,
    private val maxSize: Int,
) {
    private val chosen = mutableListOf<Pair<ActivationOption, Int>>()
    private val usedPerClass = IntArray(supply.capacity.size)
    private var creatureDrain = 0
    private val found = mutableListOf<List<ManaActivation>>()

    fun run(from: Int) {
        collectIfLegal()
        if (chosen.size == maxSize) return
        for (index in from until usable.size) {
            val option = usable[index]
            val classIndex = option.first.classIndex
            // Two capacity bounds, both hard: a class cannot be activated more often than it has
            // usable members, and (CR 602.1) the seat cannot tap more untapped creatures than it
            // controls — whether the taps are the sources' own {T} costs or a second creature. The
            // membership bound is read first because the creature drain indexes the member it would
            // use, which only exists while that bound holds.
            val withinClass = usedPerClass[classIndex] < supply.capacity[classIndex]
            val drain = if (withinClass) creatureDrainOf(option.first, usedPerClass[classIndex]) else 0
            val affordable = withinClass && creatureDrain + drain <= supply.untappedCreatures
            if (affordable) {
                usedPerClass[classIndex] += 1
                creatureDrain += drain
                chosen += option
                run(index)
                chosen.removeAt(chosen.lastIndex)
                creatureDrain -= drain
                usedPerClass[classIndex] -= 1
            }
        }
    }

    fun sets(): List<List<ManaActivation>> = found.toList()

    /**
     * How many of the seat's untapped creatures the [memberIndex]th use of [option]'s class consumes:
     * the member itself when the alternative taps or sacrifices it and it is an untapped creature, plus
     * one for a "Tap an untapped creature you control" component.
     *
     * The counting is exact rather than conservative, and the argument is that all the consumptions are
     * *distinct objects*: a source tapped for its own `{T}`, a source sacrificed, and a creature tapped
     * as somebody's second cost are three different permanents (the source is already tapped by its own
     * `{T}` when the second component is paid, so it can never be its own helper). With no type or
     * restriction on which creature may be tapped, Hall's condition on that budget degenerates to the
     * plain count, so "drain ≤ untapped creatures" is both necessary and sufficient
     * (docs/design/mana-payment.md §11.3).
     */
    private fun creatureDrainOf(
        option: ActivationOption,
        memberIndex: Int,
    ): Int {
        val self = if (option.consumesSource && supply.creatureMembers[option.classIndex][memberIndex]) 1 else 0
        return self + if (option.tapsAnotherCreature) 1 else 0
    }

    private fun collectIfLegal() {
        val activations = chosen.map { it.first.activation }
        // CR 601.2g: aggregate arithmetic is not enough once an activation can cost mana — the set
        // must also admit an order in which every cost is payable when it is paid, or it funds itself
        // out of nothing (docs/design/mana-payment.md §11.2).
        val legal =
            covers() && noIdleActivation() && manaActivationOrder(supply.pool, activations) != null
        if (legal) found += activations
    }

    /**
     * Whether the pool plus the chosen activations' yields meet the demand **and** those activations'
     * own mana costs, type by type. Types do not substitute for one another, so coverage still
     * decomposes type by type; what `FW-MANACOST` added is the second term on the demand side.
     */
    private fun covers(): Boolean {
        val available = IntArray(ManaType.entries.size)
        val required = demand.copyOf()
        supply.pool.forEach { (type, count) -> available[type.ordinal] += count }
        chosen.forEach { (option, _) ->
            option.produces.forEach { available[it.ordinal] += 1 }
            option.spends.forEach { required[it.ordinal] += 1 }
        }
        return ManaType.entries.all { required[it.ordinal] <= available[it.ordinal] }
    }

    /** The §4 no-idle rule: Hall's theorem while every activation is free, an exact matching once one is not. */
    private fun noIdleActivation(): Boolean =
        if (chosen.none { it.first.spends.isNotEmpty() }) {
            everyActivationSpends(chosen.map { it.second and demandMask }, demand)
        } else {
            everyActivationSpendsWithCosts(
                chosen.map { it.first.produces },
                chosen.map { it.first.spends },
                demand,
            )
        }
}
