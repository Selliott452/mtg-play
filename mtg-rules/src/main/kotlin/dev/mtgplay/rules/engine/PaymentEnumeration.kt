package dev.mtgplay.rules.engine

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaSymbol
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.decision.SourceClassKey
import dev.mtgplay.rules.decision.SymbolPayment

/*
 * Payment-plan enumeration (CR 601.2g–h), implementing docs/design/mana-payment.md: every
 * distinct way to pay a cost from the caster's usable mana sources, pooled mana, and (for
 * Phyrexian symbols) life — interchangeable sources collapsed into classes, plans
 * deduplicated and deterministically ordered (ADR-005, ADR-006).
 *
 * Since P8.3 a plan is split into CR 601.2g activations and CR 601.2h payments, so one
 * activation may pay several symbols; the activation half of the search lives in
 * ActivationEnumeration.kt.
 */

/** The life a Phyrexian symbol's alternative costs (CR 107.4). */
internal const val PHYREXIAN_LIFE_COST: Int = 2

/**
 * One class of payment-equivalent mana sources: the [key] every member shares and the members
 * themselves, in battlefield order (docs/design/mana-payment.md — same printed card, same
 * production profile, same CR 605.1b bonus, usable, same controller).
 *
 * @property untappedCreatureMembers parallel to [members]: whether each is an untapped creature right
 *   now. It is **not** part of the equivalence key — two Llanowar Elves are payment-equivalent whether
 *   or not one of them is the one a Saruli Caretaker will tap — but the seat's untapped creatures are a
 *   budget the whole plan shares, and members are spent in battlefield order, so the drain of the
 *   `k`th use of this class is read off entry `k` (docs/design/mana-payment.md §11.3).
 */
internal data class SourceClass(
    val key: SourceClassKey,
    val members: List<ObjectId>,
    val untappedCreatureMembers: List<Boolean> = List(members.size) { false },
)

/**
 * The payment-equivalence classes of [seat]'s usable battlefield mana sources, ordered by
 * first appearance in battlefield order (the stable class order of
 * docs/design/mana-payment.md). Controller is owner until control-changing effects exist
 * (Phase 4+).
 *
 * [reserved] names objects another component of the *same* cost has already claimed, which must
 * therefore not fund its mana component ([manaSourcesReservedBy], docs/design/mana-payment.md §2.2).
 * The exclusion is by **object**, not by class: it shrinks the class's capacity by one and removes
 * the class entirely only when it had a single member — which is exactly right, because a second
 * copy of the same card is a perfectly good payer.
 */
internal fun manaSourceClasses(
    state: GameState,
    seat: PlayerId,
    reserved: Set<ObjectId> = emptySet(),
): List<SourceClass> {
    val classes = LinkedHashMap<SourceClassKey, MutableList<GameObject>>()
    state.sharedZones.battlefield
        .filter { it.owner == seat && it.id !in reserved && manaSourceUsable(state, it) }
        .forEach { obj ->
            sourceClassKeyOf(state, obj)?.let { key ->
                classes.getOrPut(key) { mutableListOf() }.add(obj)
            }
        }
    return classes.map { (key, members) ->
        SourceClass(
            key = key,
            members = members.map { it.id },
            untappedCreatureMembers = members.map { !it.tapped && isCreature(state, it) },
        )
    }
}

/**
 * Enumerates every distinct payment plan for [seat] paying [cost] in [state], per
 * docs/design/mana-payment.md: a two-level search with **payments outer, activations inner**.
 * Symbols are visited in printed order (generics expanded in place) and each symbol's candidate
 * payments tried in a fixed order — mana types in WUBRG-then-colorless order, the Phyrexian life
 * alternative last — with assignments non-decreasing within runs of identical symbols; for each
 * payment assignment the activation multisets that cover it are enumerated in the canonical order
 * of [enumerateActivationSets]. The result is duplicate-free and deterministically ordered by
 * construction (§3.3). Empty exactly when the cost is unaffordable, which is what excludes the
 * cast from enumeration (ADR-005).
 *
 * Life legality (CR 118.8): a plan's summed life payments must not exceed [seat]'s life total;
 * paying down to 0 or less is legal and the CR 704.5a state-based action follows.
 *
 * [reserved] excludes battlefield objects a *sibling component of the same cost* has claimed
 * ([manaSourcesReservedBy]). It is empty for a spell's mana cost and non-empty only for an
 * activated ability whose cost also taps or sacrifices its own source — the trap the
 * gauntlet triage records as T17, and an ADR-005 defect rather than a rules corner, because the
 * offending plan was *enumerated* and then failed to execute.
 */
internal fun enumeratePaymentPlans(
    state: GameState,
    seat: PlayerId,
    cost: ManaCost,
    reserved: Set<ObjectId> = emptySet(),
): List<PaymentPlan> {
    val player = state.player(seat)
    val supply =
        manaSupply(
            pool = player.manaPool.groupingBy { it }.eachCount(),
            classes = manaSourceClasses(state, seat, reserved),
            life = player.life,
            // CR 602.1: the budget Saruli Caretaker's "Tap an untapped creature you control" and every
            // creature source's own {T} draw on. Counted over the whole battlefield rather than over the
            // source classes, because a creature that is no mana source at all is still a legal thing to
            // tap (docs/design/mana-payment.md §11.3).
            untappedCreatures = untappedCreatures(state, seat).size,
        )
    val units = expandToUnits(cost)
    val search = PaymentSearch(units, units.map { candidatesFor(it, supply.obtainable) }, supply)
    search.run(index = 0, minCandidate = 0)
    return search.plans()
}

/**
 * Expands [cost] into per-unit symbols in printed order: `{N}` becomes `N` copies of `{1}` (so
 * all expanded generic units compare equal, which is what the identical-symbol dedup rule keys
 * on) and `{0}` contributes nothing; every other symbol is its own unit.
 */
internal fun expandToUnits(cost: ManaCost): List<ManaSymbol> =
    cost.symbols.flatMap { symbol ->
        when (symbol) {
            is ManaSymbol.Generic -> List(symbol.amount) { ManaSymbol.Generic(1) }
            is ManaSymbol.Colored, ManaSymbol.Colorless, is ManaSymbol.Hybrid, is ManaSymbol.Phyrexian ->
                listOf(symbol)
        }
    }

/** The [ManaType] one mana of [color] is (CR 106.1b). */
internal fun manaTypeOf(color: Color): ManaType =
    when (color) {
        Color.WHITE -> ManaType.WHITE
        Color.BLUE -> ManaType.BLUE
        Color.BLACK -> ManaType.BLACK
        Color.RED -> ManaType.RED
        Color.GREEN -> ManaType.GREEN
    }

/**
 * The mana types one [symbol] accepts (CR 107.4), before the life alternative. Shared with the
 * activation search, which asks the same question of a mana ability's *own* cost (`FW-MANACOST`).
 */
internal fun payableTypes(symbol: ManaSymbol): List<ManaType> =
    when (symbol) {
        is ManaSymbol.Colored -> listOf(manaTypeOf(symbol.color))
        ManaSymbol.Colorless -> listOf(ManaType.COLORLESS)
        // CR 107.4d: generic is payable by mana of any type.
        is ManaSymbol.Generic -> ManaType.entries
        // CR 107.4: a hybrid symbol is payable with either of its component colors.
        is ManaSymbol.Hybrid -> listOf(manaTypeOf(symbol.first), manaTypeOf(symbol.second))
        is ManaSymbol.Phyrexian -> listOf(manaTypeOf(symbol.color))
    }

/**
 * The candidate payments for one cost unit, in the fixed enumeration order of
 * docs/design/mana-payment.md: mana types in WUBRG-then-colorless order, the Phyrexian 2-life
 * alternative last. Types [obtainable] names are the only ones any plan could supply, so
 * restricting to them prunes without removing a legal plan. The non-decreasing rule for identical
 * symbols indexes into this list.
 */
private fun candidatesFor(
    symbol: ManaSymbol,
    obtainable: Set<ManaType>,
): List<SymbolPayment> =
    buildList {
        payableTypes(symbol)
            .sortedBy(ManaType::ordinal)
            .filter { it in obtainable }
            .forEach { add(SymbolPayment.WithMana(it)) }
        // CR 107.4: the Phyrexian alternative — 2 life instead of the mana; always last.
        if (symbol is ManaSymbol.Phyrexian) add(SymbolPayment.WithTwoLife)
    }

/**
 * The depth-first search of docs/design/mana-payment.md §3.1 over the units' candidate payments.
 * `minCandidate` carries the previous unit's candidate index and constrains a unit only when its
 * symbol equals the previous one — the non-decreasing rule that suppresses permutation
 * duplicates. Every legality clause is a predicate on the whole plan and is invariant under
 * permuting payments within a run, so the canonical representative is feasible exactly when any
 * permutation of it is (§3.1) — which is what makes the rule lossless now that one activation can
 * pay several symbols.
 *
 * The running demand and life are pruned against [ManaSupply.maxAvailable] as the search
 * descends; a leaf then hands its demand to [enumerateActivationSets] for the inner half.
 */
private class PaymentSearch(
    private val units: List<ManaSymbol>,
    private val candidates: List<List<SymbolPayment>>,
    private val supply: ManaSupply,
) {
    private val chosen = mutableListOf<SymbolPayment>()
    private val demand = IntArray(ManaType.entries.size)
    private var lifeCommitted = 0
    private val found = mutableListOf<PaymentPlan>()

    fun run(
        index: Int,
        minCandidate: Int,
    ) {
        if (index == units.size) {
            emitPlans()
            return
        }
        val startsRun = index == 0 || units[index] != units[index - 1]
        for (candidateIndex in (if (startsRun) 0 else minCandidate) until candidates[index].size) {
            val payment = candidates[index][candidateIndex]
            if (!take(payment)) continue
            run(index + 1, candidateIndex)
            release(payment)
        }
    }

    fun plans(): List<PaymentPlan> = found.toList()

    /** Commits [payment] to the partial plan, or leaves the search state untouched and returns false. */
    private fun take(payment: SymbolPayment): Boolean {
        val fits =
            when (payment) {
                is SymbolPayment.WithMana -> {
                    val ordinal = payment.mana.ordinal
                    demand[ordinal] + 1 <= supply.maxAvailable[ordinal]
                }
                // CR 118.8: the summed life payments may not exceed the caster's life total.
                SymbolPayment.WithTwoLife -> lifeCommitted + PHYREXIAN_LIFE_COST <= supply.life
            }
        if (!fits) return false
        when (payment) {
            is SymbolPayment.WithMana -> demand[payment.mana.ordinal] += 1
            SymbolPayment.WithTwoLife -> lifeCommitted += PHYREXIAN_LIFE_COST
        }
        chosen += payment
        return true
    }

    private fun release(payment: SymbolPayment) {
        when (payment) {
            is SymbolPayment.WithMana -> demand[payment.mana.ordinal] -= 1
            SymbolPayment.WithTwoLife -> lifeCommitted -= PHYREXIAN_LIFE_COST
        }
        chosen.removeAt(chosen.lastIndex)
    }

    private fun emitPlans() {
        val payments = chosen.toList()
        enumerateActivationSets(supply, demand).forEach { activations ->
            found += PaymentPlan(activations, payments)
        }
    }
}
