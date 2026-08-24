package dev.mtgplay.rules.engine

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaSymbol
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.ManaSourceChoice
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.decision.SourceClassKey
import dev.mtgplay.rules.decision.SymbolPayment

/*
 * Payment-plan enumeration (CR 601.2g–h), implementing docs/design/mana-payment.md: every
 * distinct way to pay a cost from the caster's untapped sources, pooled mana, and (for
 * Phyrexian symbols) life — interchangeable sources collapsed into classes, plans
 * deduplicated and deterministically ordered (ADR-005, ADR-006).
 */

/** The life a Phyrexian symbol's alternative costs (CR 107.4). */
internal const val PHYREXIAN_LIFE_COST: Int = 2

/**
 * One class of payment-equivalent mana sources: the [key] every member shares and the members
 * themselves, in battlefield order (docs/design/mana-payment.md — same printed card, same
 * production profile, untapped, same controller).
 */
internal data class SourceClass(
    val key: SourceClassKey,
    val members: List<ObjectId>,
)

/**
 * The payment-equivalence classes of [seat]'s untapped battlefield mana sources, ordered by
 * first appearance in battlefield order (the stable class order of
 * docs/design/mana-payment.md). Controller is owner until control-changing effects exist
 * (Phase 4+).
 */
internal fun manaSourceClasses(
    state: GameState,
    seat: PlayerId,
): List<SourceClass> {
    val classes = LinkedHashMap<SourceClassKey, MutableList<ObjectId>>()
    state.sharedZones.battlefield
        // A tap source must be untapped; a sacrifice source (Eldrazi Spawn) is usable tapped or not.
        .filter { it.owner == seat && (!it.tapped || isSacrificeSource(state, it.id)) }
        .forEach { obj ->
            productionProfile(state, obj)?.let { profile ->
                val bonus = triggeredManaBonus(state, obj.id)
                val key = SourceClassKey(obj.card, profile, bonus, isSacrificeSource(state, obj.id))
                classes.getOrPut(key) { mutableListOf() }.add(obj.id)
            }
        }
    return classes.map { (key, members) -> SourceClass(key, members.toList()) }
}

/**
 * Whether the battlefield source [id] produces mana by being **sacrificed** rather than tapped
 * (CR 605.1a) — an Eldrazi Spawn's "Sacrifice this token: Add {C}". True when its layered mana
 * abilities are non-empty and every one is a sacrifice ability; the MVP pool never mixes tap and
 * sacrifice mana abilities on one source, so this all-or-nothing test is exact.
 */
internal fun isSacrificeSource(
    state: GameState,
    id: ObjectId,
): Boolean {
    val abilities = layeredCharacteristics(state, id).manaAbilities
    return abilities.isNotEmpty() && abilities.all { it.viaSacrifice }
}

/**
 * The extra mana a tap of the battlefield source [sourceId] adds *in addition to* its primary
 * production (CR 605.1b): the [dev.mtgplay.core.definition.TriggeredManaAbility]s of every Aura attached
 * to it — Utopia Sprawl's "add one mana of the chosen colour" on an enchanted Forest, Wild Growth's
 * additional `{G}` on an enchanted land — expanded to [ManaType]s, in battlefield-then-ability order.
 * Empty for a source with no such Aura. This is the [SourceClassKey.bonus] that keeps an enchanted
 * Forest a distinct source class, and the mana [resolveTapForMana] floats into the pool after the
 * primary mana.
 */
internal fun triggeredManaBonus(
    state: GameState,
    sourceId: ObjectId,
): List<ManaType> =
    state.sharedZones.battlefield
        .filter { it.attachedTo == sourceId }
        .flatMap { aura ->
            state.definitions[aura.card]?.triggeredManaAbilities.orEmpty().flatMap { ability ->
                when (ability) {
                    is dev.mtgplay.core.definition.TriggeredManaAbility.AddChosenColor -> {
                        val color = aura.chosenColor
                        if (color == null) emptyList() else List(ability.amount) { manaTypeOf(color) }
                    }

                    is dev.mtgplay.core.definition.TriggeredManaAbility.AddFixedMana ->
                        List(ability.amount) { ability.manaType }
                }
            }
        }

/**
 * The production profile of the battlefield object [obj]: the canonical (WUBRG-then-colorless,
 * CR 105.1) list of mana types its tap-for-mana abilities can add, or `null` if it is no mana source
 * — no definition, or none with mana abilities. Reads the object's **layered** mana abilities
 * ([layeredCharacteristics]), so a land granted "{T}: add one mana of any color" by an Abundant
 * Growth (CR 613 layer 6) taps for the granted colors here (docs/design/layer-system.md §6). Utopia
 * Sprawl's *triggered* mana ability (CR 605.1b) and its chosen colour remain Phase 5 — this seam
 * carries only the plain, non-triggered layer-6 grant. The profile *shape* is what the payment
 * equivalence relation keys on, so a grant changes an object's class without reshaping anything.
 */
internal fun productionProfile(
    state: GameState,
    obj: GameObject,
): List<ManaType>? {
    val producible =
        layeredCharacteristics(state, obj.id)
            .manaAbilities
            .flatMap { it.options }
            .toSet()
    return if (producible.isEmpty()) null else ManaType.entries.filter { it in producible }
}

/**
 * Enumerates every distinct payment plan for [seat] paying [cost] in [state], per
 * docs/design/mana-payment.md: symbols in printed order (generics expanded in place),
 * candidates per symbol in the fixed order — mana types in WUBRG-then-colorless order, pool
 * before tapping, classes in battlefield order, the Phyrexian life alternative last — with
 * assignments non-decreasing within runs of identical symbols, which makes the result
 * duplicate-free and deterministically ordered by construction. Empty exactly when the cost is
 * unaffordable, which is what excludes the cast from enumeration (ADR-005).
 *
 * Life legality (CR 118.8): a plan's summed life payments must not exceed [seat]'s life total;
 * paying down to 0 or less is legal and the CR 704.5a state-based action follows.
 */
internal fun enumeratePaymentPlans(
    state: GameState,
    seat: PlayerId,
    cost: ManaCost,
): List<PaymentPlan> {
    val classes = manaSourceClasses(state, seat)
    val player = state.player(seat)
    val units = expandToUnits(cost)
    val search = PlanSearch(units, units.map { candidatesFor(it, classes) })
    search.run(
        index = 0,
        minCandidate = 0,
        resources =
            PaymentResources(
                pool = player.manaPool.groupingBy { it }.eachCount(),
                classRemaining = classes.associate { it.key to it.members.size },
                lifeRemaining = player.life,
            ),
    )
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
 * The static candidate payments for one cost unit, in the fixed enumeration order of
 * docs/design/mana-payment.md. Availability against resources is checked during the search;
 * this list is the *ordering*, and the non-decreasing rule for identical symbols indexes
 * into it.
 */
private fun candidatesFor(
    symbol: ManaSymbol,
    classes: List<SourceClass>,
): List<SymbolPayment> {
    val payable: List<ManaType> =
        when (symbol) {
            is ManaSymbol.Colored -> listOf(manaTypeOf(symbol.color))
            ManaSymbol.Colorless -> listOf(ManaType.COLORLESS)
            // CR 107.4d: generic is payable by mana of any type.
            is ManaSymbol.Generic -> ManaType.entries
            // CR 107.4: a hybrid symbol is payable with either of its component colors.
            is ManaSymbol.Hybrid -> listOf(manaTypeOf(symbol.first), manaTypeOf(symbol.second))
            is ManaSymbol.Phyrexian -> listOf(manaTypeOf(symbol.color))
        }
    return buildList {
        for (type in payable.sortedBy(ManaType::ordinal)) {
            add(SymbolPayment.WithMana(type, ManaSourceChoice.FromPool))
            for (sourceClass in classes) {
                if (type in sourceClass.key.profile) {
                    add(SymbolPayment.WithMana(type, ManaSourceChoice.ByTapping(sourceClass.key)))
                }
            }
        }
        // CR 107.4: the Phyrexian alternative — 2 life instead of the mana; always last.
        if (symbol is ManaSymbol.Phyrexian) add(SymbolPayment.WithTwoLife)
    }
}

/**
 * What remains available to pay with at one point of the search: pooled mana by type, untapped
 * members per source class, and the life not yet committed (CR 118.8).
 */
private data class PaymentResources(
    val pool: Map<ManaType, Int>,
    val classRemaining: Map<SourceClassKey, Int>,
    val lifeRemaining: Int,
) {
    /** The resources after drawing one pooled [mana], or `null` if none remains. */
    fun spendingPooled(mana: ManaType): PaymentResources? {
        val available = pool[mana] ?: 0
        return if (available > 0) copy(pool = pool + (mana to available - 1)) else null
    }

    /** The resources after tapping one member of [sourceClass], or `null` if none remains. */
    fun tappingMemberOf(sourceClass: SourceClassKey): PaymentResources? {
        val available = classRemaining[sourceClass] ?: 0
        return if (available > 0) copy(classRemaining = classRemaining + (sourceClass to available - 1)) else null
    }

    /** The resources after committing 2 life (CR 107.4), or `null` if life cannot cover it. */
    fun payingTwoLife(): PaymentResources? =
        if (lifeRemaining >= PHYREXIAN_LIFE_COST) copy(lifeRemaining = lifeRemaining - PHYREXIAN_LIFE_COST) else null
}

/**
 * The depth-first search of docs/design/mana-payment.md over the units' candidate payments.
 * `minCandidate` carries the previous unit's candidate index and constrains a unit only when
 * its symbol equals the previous one — the non-decreasing rule that suppresses permutation
 * duplicates, making the collected plans duplicate-free and deterministically ordered by
 * construction.
 */
private class PlanSearch(
    private val units: List<ManaSymbol>,
    private val candidates: List<List<SymbolPayment>>,
) {
    private val chosen = mutableListOf<SymbolPayment>()
    private val found = mutableListOf<PaymentPlan>()

    fun run(
        index: Int,
        minCandidate: Int,
        resources: PaymentResources,
    ) {
        if (index == units.size) {
            found += PaymentPlan(chosen.toList())
            return
        }
        val startsRun = index == 0 || units[index] != units[index - 1]
        val from = if (startsRun) 0 else minCandidate
        for (candidateIndex in from until candidates[index].size) {
            val payment = candidates[index][candidateIndex]
            val remaining = resources.consuming(payment) ?: continue
            chosen += payment
            run(index + 1, candidateIndex, remaining)
            chosen.removeAt(chosen.lastIndex)
        }
    }

    fun plans(): List<PaymentPlan> = found.toList()

    private fun PaymentResources.consuming(payment: SymbolPayment): PaymentResources? =
        when (payment) {
            is SymbolPayment.WithMana ->
                when (val source = payment.source) {
                    ManaSourceChoice.FromPool -> spendingPooled(payment.mana)
                    is ManaSourceChoice.ByTapping -> tappingMemberOf(source.sourceClass)
                }
            SymbolPayment.WithTwoLife -> payingTwoLife()
        }
}
