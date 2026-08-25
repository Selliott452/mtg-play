package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.CostReduction
import dev.mtgplay.core.definition.CountCondition
import dev.mtgplay.core.definition.SpellCostReduction
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaSymbol
import dev.mtgplay.core.state.GameState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/*
 * The CR 601.2f hook: dynamic cost modification, implementing docs/design/cost-modification.md.
 *
 * > CR 601.2f — "The total cost is the mana cost or alternative cost (as determined in rule 601.2b),
 * > plus all additional costs and cost increases, and minus all cost reductions. If multiple cost
 * > reductions apply, the player may apply them in any order. If the mana component of the total cost
 * > is reduced to nothing by cost reduction effects, it is considered to be {0}. It can't be reduced
 * > to less than {0}. Once the total cost is determined, any effects that directly affect the total
 * > cost are applied. Then the resulting total cost becomes 'locked in.' If effects would change the
 * > total cost after this time, they have no effect."
 *
 * **One cost function, four call sites, zero re-derivation.** [totalCost] is the only place a spell is
 * priced. Cast legality (ActionEnumeration.castIsLegal), permission legality (CastLegality), request
 * derivation (PendingCastRequest), and execution (CastingPipeline) all call it. Before this framework
 * those four agreed because the expression was a constant (`permission.cost ?: definition.manaCost`);
 * the moment the cost depends on board state, agreement becomes a property that can silently break,
 * and the failure mode is precisely ADR-005's silent defect — an option enumerated against one cost
 * and paid against another. Nothing here may be inlined back into a call site.
 *
 * **Lock-in is positional, not stored** (design note §2.1, open question 1 resolved as recommended).
 * The determined cost is a pure function of the paused state, which ADR-004 requires every pending
 * request to be re-derivable from; storing it on `PendingCast` would add a second source of truth, a
 * replay-fingerprint token, and an invariant. What enforces lock-in instead is *where* the pipeline
 * calls this — before every cost-payment stage (see `executeCastPipeline`) — so no payment can
 * re-price the spell.
 */

/**
 * The total cost [seat] pays to cast [definition] via [permission] (or normally, when it is `null`),
 * with [castObjectId] the object being cast (CR 601.2f).
 *
 * The formula, in the CR's printed order:
 *
 * 1. the alternative cost when one is used, else the printed mana cost (CR 601.2b, CR 118.9 — an
 *    alternative cost **replaces** the printed one entirely);
 * 2. **plus cost increases** — the slot exists in the formula and is deliberately empty: nothing in
 *    the pool increases a cost, and no declaration type can express one, so an increase is
 *    unrepresentable rather than approximated. Ward (CR 702.21a) is *not* an increase — it is a
 *    triggered pay-or-be-countered ability, which is why Tolarian Terror is not encodable here;
 * 3. **minus cost reductions** — the spell's own static ability ([SpellDefinition.costReduction]) plus
 *    every battlefield reducer [seat] controls ([spellCostReductions]), summed;
 * 4. clamped so the mana component never falls below `{0}` ([reduceGeneric]).
 *
 * **Every reduction is an amount of generic mana**, so CR 118.7a confines all of them to the generic
 * component and the sum is order-independent: `max(0, generic − Σ reductions)` is integer subtraction
 * with one clamp at the end, which commutes. That is what makes CR 601.2f's "the player may apply them
 * in any order" surface **no decision** — the freedom is real but unobservable, so ADR-005 loses no
 * legal outcome by not enumerating it. It stops being true the moment a coloured (CR 118.7b–d) or
 * hybrid (CR 118.7e, which would introduce a genuine new decision) reduction enters the pool; those
 * shapes are unrepresentable in [CostReduction] on purpose, and adding one must revisit this comment.
 *
 * [castObjectId] is excluded from every zone count (CR 601.2a): the card has left its source zone by
 * the time the cost is determined, so it never counts itself. For a hand cast this is invisible — a
 * hand card is in no counted zone either way — but for a graveyard cast of a spell whose reduction
 * counts the graveyard it is the whole difference, and naming it makes the gathering-time and
 * execution-time answers identical by construction rather than by luck of stage placement.
 */
internal fun totalCost(
    state: GameState,
    seat: PlayerId,
    definition: SpellDefinition,
    permission: CastingPermission?,
    castObjectId: ObjectId?,
): ManaCost {
    val base =
        permission?.cost
            ?: definition.manaCost
            ?: error(
                "CR 601.2f: ${definition.characteristics.name} has no mana cost and no alternative cost to " +
                    "cast it with",
            )
    val reduction =
        selfReduction(state, seat, definition, castObjectId) +
            otherObjectReduction(state, seat, definition)
    return reduceGeneric(base, reduction)
}

/**
 * The reduction the spell's **own** static ability contributes (CR 604.5; CR 702.41a for affinity),
 * read once against the paused state with [castObjectId] excluded. Zero for a spell with no such
 * ability.
 */
private fun selfReduction(
    state: GameState,
    seat: PlayerId,
    definition: SpellDefinition,
    castObjectId: ObjectId?,
): Int =
    when (val declared = definition.costReduction) {
        null -> 0
        is CostReduction.PerMatching ->
            declared.amountPerMatch *
                countMatching(state, seat, declared.scope, declared.predicate, excluding = castObjectId)
        is CostReduction.IfAll ->
            if (declared.conditions.all { holds(state, seat, it, castObjectId) }) declared.amount else 0
    }

/** Whether [condition]'s count threshold is met right now, with [castObjectId] excluded (CR 601.2a). */
private fun holds(
    state: GameState,
    seat: PlayerId,
    condition: CountCondition,
    castObjectId: ObjectId?,
): Boolean =
    countMatching(state, seat, condition.scope, condition.predicate, excluding = castObjectId) >=
        condition.atLeast

/**
 * The reduction contributed by battlefield permanents [seat] controls whose static abilities reduce
 * the spells they cast (CR 604.5, CR 613.11) — Sunscape Familiar. Summed over every matching
 * permanent, so two Familiars reduce by two.
 *
 * **The spell's colours come from its printed mana cost (CR 202.2), never from the cost being paid.**
 * `characteristics.colors` is the printed derivation; reading `permission.cost.colors` instead would
 * make a plot cast (a `{0}` alternative cost) colourless and silently stop matching, and would do the
 * same to any future alternative cost of a different colour than the card. One line to get wrong, and
 * invisible when wrong.
 */
private fun otherObjectReduction(
    state: GameState,
    seat: PlayerId,
    definition: SpellDefinition,
): Int {
    val colors = definition.characteristics.colors
    return state.sharedZones.battlefield
        .filter { it.owner == seat }
        .sumOf { permanent ->
            state.definitions[permanent.card]
                ?.spellCostReductions
                ?.filter { reducer -> reducesSpellOf(reducer, colors) }
                ?.sumOf { it.amount }
                ?: 0
        }
}

/** Whether [reducer] applies to a spell whose printed colours are [colors] (CR 202.2). */
private fun reducesSpellOf(
    reducer: SpellCostReduction,
    colors: Set<Color>,
): Boolean = reducer.spellColors.any { it in colors }

/**
 * Reduces [cost] by [amount] generic mana (CR 118.7a, CR 601.2f), clamped at `{0}`.
 *
 * **CR 118.7a** — "Effects that reduce a cost by an amount of generic mana affect only the generic
 * mana component of that cost. They can't affect the colored or colorless mana components of that
 * cost." Cryptic Serpent `{5}{U}{U}` reduced by seven is `{U}{U}`, never less; the coloured floor is
 * the rule, not an accident of clamping.
 *
 * **CR 601.2f** — "If the mana component of the total cost is reduced to nothing by cost reduction
 * effects, it is considered to be {0}. It can't be reduced to less than {0}."
 *
 * Two shapes of zero, distinguished (design note §3): `ManaCost` requires a **non-empty** symbol list
 * — "a card with no mana cost is modeled as the absence of a ManaCost" — so reducing `{7}` by seven
 * must produce a single `Generic(0)` and not an empty list, which would throw in `ManaCost.init`.
 * Reducing `{5}{U}{U}` by five must produce `{U}{U}` and **not** `{0}{U}{U}`: `expandToUnits` maps
 * `Generic(0)` to zero units so both pay identically, but `render()` would print the dead `{0}` in the
 * CLI menu and on the wire. So a zeroed generic symbol is dropped unless it is the entire cost.
 *
 * Generic symbols are consumed in printed order. With a single generic symbol per printed cost — true
 * of every card in the pool — order is immaterial; with several it still cannot matter, because only
 * the total generic remaining is observable.
 */
internal fun reduceGeneric(
    cost: ManaCost,
    amount: Int,
): ManaCost {
    require(amount >= 0) { "CR 601.2f: a cost reduction is non-negative, was $amount" }
    if (amount == 0) return cost
    var remaining = amount
    val reduced =
        cost.symbols.mapNotNull { symbol ->
            when (symbol) {
                is ManaSymbol.Generic -> {
                    // CR 601.2f: the clamp — a symbol absorbs at most what it has.
                    val taken = minOf(symbol.amount, remaining)
                    remaining -= taken
                    (symbol.amount - taken).takeIf { it > 0 }?.let { ManaSymbol.Generic(it) }
                }
                // CR 118.7a: coloured, colorless, hybrid, and Phyrexian components are untouchable.
                is ManaSymbol.Colored, ManaSymbol.Colorless, is ManaSymbol.Hybrid, is ManaSymbol.Phyrexian ->
                    symbol
            }
        }
    // CR 601.2f: a mana component reduced to nothing is {0}, which is a real one-symbol cost here.
    return if (reduced.isEmpty()) {
        ManaCost(persistentListOf(ManaSymbol.Generic(0)))
    } else {
        ManaCost(reduced.toPersistentList())
    }
}
