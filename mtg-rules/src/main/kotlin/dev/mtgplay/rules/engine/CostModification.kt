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
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
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
 * The total cost [seat] pays to cast [subject] with the CR 601.2b [announcements] settled so far
 * (CR 601.2f).
 *
 * The formula, in the CR's printed order:
 *
 * 1. the alternative cost when one is used, else the printed mana cost (CR 601.2b, CR 118.9 — an
 *    alternative cost **replaces** the printed one entirely);
 * 2. **with the announced value of X substituted in** (CR 107.3, CR 601.2b — [chosenX]), which is
 *    where a variable cost stops being a variable. It happens *first*, because everything below
 *    operates on a cost of real symbols: the value was announced before the total cost was determined,
 *    exactly as CR 601.2b sequences it, and X becomes that much generic mana;
 * 3. **plus additional costs** — the **kicker** cost when the caster announced they are paying it
 *    (CR 702.33a, [kicked]). CR 601.2f puts additional costs in the formula ahead of reductions, and
 *    kicker is one: "You may pay an additional [cost] as you cast this spell";
 * 4. **plus cost increases** — populated by `W10-D` with exactly one shape,
 *    [dev.mtgplay.core.definition.StackTargetTax]: a spell on the stack taxing spells that target *it*
 *    (Kaervek's Torch). Read from [CastSubject.targets], so it is well-defined here for the same reason
 *    a target-conditional reduction is — CR 601.2c has already run. Ward (CR 702.21a) is still *not* an
 *    increase and Tolarian Terror is still not encodable here: ward is a triggered pay-or-be-countered
 *    ability that fires after the spell is cast, not a change to what casting it costs;
 * 5. **minus cost reductions** — the spell's own static ability ([SpellDefinition.costReduction]) plus
 *    every battlefield reducer [seat] controls ([spellCostReductions]), summed. Since `FW-TGTCOND` the
 *    spell's own reduction may read [CastSubject.targets] rather than a zone — Ride's End's "{3} less if
 *    it targets a tapped permanent" — which is well-defined here because CR 601.2c has already run;
 * 6. clamped so the mana component never falls below `{0}` ([reduceGeneric]).
 *
 * **A reduction may eat into the X mana, and that is correct.** By the time step 5 runs, an announced
 * X of 3 is indistinguishable from a printed `{3}`, so CR 118.7a confines the reduction to it exactly as
 * it would to any other generic component. The announcement is not protected from reductions, and
 * nothing in CR 601.2f suggests it should be.
 *
 * **Every reduction is an amount of generic mana**, so CR 118.7a confines all of them to the generic
 * component and the sum is order-independent: `max(0, generic + Σ increases − Σ reductions)` is integer
 * arithmetic with one clamp at the end, which commutes *among the reductions*. That is what makes
 * CR 601.2f's "the player may apply them in any order" surface **no decision** — the freedom is real but
 * unobservable, so ADR-005 loses no legal outcome by not enumerating it. It stops being true the moment a
 * coloured (CR 118.7b–d) or hybrid (CR 118.7e, which would introduce a genuine new decision) reduction
 * enters the pool; those shapes are unrepresentable in [CostReduction] on purpose, and adding one must
 * revisit this comment.
 *
 * **The increase, though, does not commute with the reductions, and `W10-D` revisited this comment to say
 * so.** The clamp is what breaks it: `{1}` taxed by two and then reduced by three is `{0}`, while reducing
 * first clamps at `{0}` and then taxes back up to `{2}`. CR 601.2f's printed order — increases, *then*
 * reductions — is therefore a rule this function implements rather than a convention it happens to follow,
 * and [increaseGeneric] is applied before [reduceGeneric] below for that reason and no other.
 *
 * [CastSubject.castObjectId] is excluded from every zone count (CR 601.2a): the card has left its source zone by
 * the time the cost is determined, so it never counts itself. For a hand cast this is invisible — a
 * hand card is in no counted zone either way — but for a graveyard cast of a spell whose reduction
 * counts the graveyard it is the whole difference, and naming it makes the gathering-time and
 * execution-time answers identical by construction rather than by luck of stage placement.
 */
internal fun totalCost(
    state: GameState,
    seat: PlayerId,
    subject: CastSubject,
    announcements: CostAnnouncements = CostAnnouncements.NONE,
): ManaCost {
    val definition = subject.definition
    // CR 601.2b then CR 601.2f: the announced value replaces the variable before anything is priced.
    val base = baseCost(definition, subject.permission).substitutingX(announcements.chosenX)
    // CR 601.2f: additional costs are added before reductions are subtracted (CR 702.33a for kicker).
    val withAdditional = if (announcements.kicked) plusKicker(definition, base) else base
    // CR 601.2f: increases before reductions, and the order is load-bearing (see the header).
    val withIncrease = increaseGeneric(withAdditional, stackTargetIncrease(state, subject))
    val reduction =
        selfReduction(state, seat, definition, subject.castObjectId, subject.targets) +
            otherObjectReduction(state, seat, definition)
    return reduceGeneric(withIncrease, reduction)
}

/**
 * The cost this cast starts from before any modification (CR 601.2b, CR 118.9): the permission's
 * alternative cost when one is used, else the printed mana cost. Fails loudly for a card with neither,
 * which enumeration must never have offered.
 */
internal fun baseCost(
    definition: SpellDefinition,
    permission: CastingPermission?,
): ManaCost =
    permission?.cost
        ?: definition.manaCost
        ?: error(
            "CR 601.2f: ${definition.characteristics.name} has no mana cost and no alternative cost to " +
                "cast it with",
        )

/**
 * [base] plus [definition]'s kicker cost (CR 702.33a, CR 601.2f) — the two costs concatenated in
 * printed order, the kicker's symbols last.
 *
 * **Concatenation, not arithmetic**, and the difference is CR 118.7's. A kicker cost is a whole mana
 * cost with its own coloured symbols — Goblin Bushwhacker's is `{R}`, not "one more generic" — so
 * `{R}` kicked becomes `{R}{R}` and demands two red, while summing mana values would have produced a
 * payable-by-anything `{2}`. The one place this is observable is exactly the pool's cards: Prohibit's
 * `{2}` kicker *is* generic and would survive either treatment, and Goblin Bushwhacker's would not.
 *
 * Fails loudly when the caster announced a kicker for a card that has none: the announcement is only
 * offered for a card with the keyword (ADR-005), so reaching here without one is an engine defect.
 */
private fun plusKicker(
    definition: SpellDefinition,
    base: ManaCost,
): ManaCost {
    val kicker =
        definition.kicker
            ?: error(
                "CR 702.33a: ${definition.characteristics.name} was cast kicked but prints no kicker cost; " +
                    "the announcement must not have been enumerated (ADR-005)",
            )
    return ManaCost((base.symbols + kicker.symbols).toPersistentList())
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
    targets: List<Target>,
): Int =
    when (val declared = definition.costReduction) {
        null -> 0
        is CostReduction.PerMatching ->
            declared.amountPerMatch *
                countMatching(state, seat, declared.scope, declared.predicate, excluding = castObjectId)
        is CostReduction.IfAll ->
            if (declared.conditions.all { holds(state, seat, it, castObjectId) }) declared.amount else 0
        // CR 121.1: an event tally on the caster, not a set of objects in a zone (`W9-F`, Deem Inferior).
        is CostReduction.PerDrawThisTurn -> declared.amountPerDraw * state.player(seat).drawsThisTurn
        // CR 601.2f/601.2c: read off the choice already made, not off the board (`FW-TGTCOND`).
        is CostReduction.IfTargets ->
            if (targets.any { satisfiesTargetCondition(state, declared.condition, it) }) declared.amount else 0
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
                // CR 601.2b: X is substituted before the cost is priced, so a reduction can never meet
                // one. Reaching here means a call site skipped `substitutingX`, which would silently
                // under-price the spell.
                ManaSymbol.X ->
                    error(
                        "CR 601.2b: the value of X must be announced and substituted before a cost is " +
                            "reduced, but ${cost.render()} still carries {X}",
                    )
            }
        }
    // CR 601.2f: a mana component reduced to nothing is {0}, which is a real one-symbol cost here.
    return if (reduced.isEmpty()) {
        ManaCost(persistentListOf(ManaSymbol.Generic(0)))
    } else {
        ManaCost(reduced.toPersistentList())
    }
}

/**
 * The generic mana this cast pays **extra** because its chosen targets name a taxing spell on the stack
 * (CR 601.2f, CR 613.11) — Kaervek's Torch's `{2}`. Zero for every cast that names none, which is every
 * cast in every game with no such spell on the stack.
 *
 * **Summed per targeted taxing spell**, not per tax: two Torches on the stack tax a spell that targets
 * both by `{4}`, and a spell that targets one of them by `{2}`. No card in the pool targets two spells,
 * so the sum is the honest general answer rather than an exercised one.
 *
 * The targets are read off [CastSubject.targets], which CR 601.2c settled before this runs — the same
 * seam a target-conditional *reduction* uses, pointed the other way.
 */
private fun stackTargetIncrease(
    state: GameState,
    subject: CastSubject,
): Int {
    val targeted = subject.targets.filterIsInstance<Target.SpellOnStack>().mapTo(mutableSetOf()) { it.id }
    if (targeted.isEmpty()) return 0
    return state.sharedZones.stack
        .filterIsInstance<StackEntry.Spell>()
        .filter { it.obj.id in targeted }
        .sumOf { it.definition.stackTargetTax?.amount ?: 0 }
}

/**
 * Increases [cost] by [amount] generic mana (CR 601.2f) — the counterpart of [reduceGeneric], and much
 * the simpler of the two: there is no CR 118.7a confinement to argue about and no floor to clamp,
 * because adding generic mana can only make a cost larger.
 *
 * The amount is folded into the **first** generic symbol when the cost has one and prepended as a new
 * symbol when it does not, so `{U}{U}` taxed by two renders as `{2}{U}{U}` — the way the card is printed
 * and the way a CLI menu must show it. The `{0}` a reduction can produce is a single `Generic(0)` symbol
 * (see [reduceGeneric]), so taxing it folds into that symbol and yields `{2}` rather than `{0}{2}`.
 *
 * Refuses an unsubstituted `{X}` for [reduceGeneric]'s reason: a cost priced before CR 601.2b's
 * announcement is the wrong cost, and folding a tax into `{X}` would hide that rather than say it.
 */
internal fun increaseGeneric(
    cost: ManaCost,
    amount: Int,
): ManaCost {
    require(amount >= 0) { "CR 601.2f: a cost increase is non-negative, was $amount" }
    if (amount == 0) return cost
    require(cost.symbols.none { it == ManaSymbol.X }) {
        "CR 601.2b: the value of X must be announced and substituted before a cost is increased, but " +
            "${cost.render()} still carries {X}"
    }
    val firstGeneric = cost.symbols.indexOfFirst { it is ManaSymbol.Generic }
    val symbols =
        if (firstGeneric < 0) {
            listOf(ManaSymbol.Generic(amount)) + cost.symbols
        } else {
            cost.symbols.mapIndexed { index, symbol ->
                if (index ==
                    firstGeneric
                ) {
                    ManaSymbol.Generic((symbol as ManaSymbol.Generic).amount + amount)
                } else {
                    symbol
                }
            }
        }
    return ManaCost(symbols.toPersistentList())
}
