package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.CostReduction
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetCondition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target

/*
 * `FW-TGTCOND`: the half of CR 601.2f that reads the spell's own chosen targets rather than the board.
 *
 * > CR 601.2c — "the player announces their choice of an appropriate . . . target . . . the player
 * > chooses the target"
 * > CR 601.2f — "The total cost is the mana cost or alternative cost . . . minus all cost reductions
 * > . . . Then the resulting total cost becomes 'locked in.'"
 *
 * Ride's End's "This spell costs {3} less to cast if it targets a tapped permanent" is the pool's only
 * printing. The *arithmetic* is [totalCost]'s and needed nothing new — CR 601.2c already precedes
 * CR 601.2f in the pipeline, so by the time the spell is priced the choice exists. What this file owns is
 * the pair of consequences that arithmetic has for **enumeration** (ADR-005), one in each direction:
 *
 * - [cheapestTargetsFor] keeps the castability gate from *under*-offering. Legality is decided before any
 *   target is chosen, so it prices the cheapest choice the board admits; pricing the printed cost would
 *   delete a payable line from the priority window.
 * - [affordableTargetOptions] keeps the target request from *over*-offering. Once the gate has admitted a
 *   cast only some of whose targets are payable, offering the rest hands the caster an option whose only
 *   outcome is an empty `ChoosePaymentPlan` — the CR answers that with the CR 601.2h/CR 728 rewind,
 *   which a gathering in progress cannot represent.
 *
 * The two are a matched pair and the argument that they are consistent is structural: the gate admits a
 * cast exactly when the cheapest-priced target is payable, and the filter keeps exactly the targets that
 * are payable, so the filtered list always contains that target and is never empty.
 *
 * Split from `CostModification.kt` for file size alone; nothing here is separable from [totalCost], which
 * remains the one place a spell is priced.
 */

/**
 * Whether the chosen [target] satisfies [condition] (CR 601.2f) — the rules half of
 * [dev.mtgplay.core.definition.CostReduction.IfTargets], read live against [state] at the one moment
 * CR 601.2f fixes the cost. Additive (`FW-TGTCOND`).
 *
 * A target that names no battlefield permanent — a player, a spell on the stack, a card in a graveyard, or
 * a permanent that has since left — satisfies nothing rather than failing loudly, and that is the printed
 * reading: "if it targets a tapped permanent" is simply false of a Ride's End pointed at anything else. It
 * is also what keeps this readable at a *legality* gate, where the candidate list may hold objects the cast
 * will never choose.
 */
internal fun satisfiesTargetCondition(
    state: GameState,
    condition: TargetCondition,
    target: Target,
): Boolean =
    when (condition) {
        // CR 110.5b: the live tapped status of the permanent the target names.
        TargetCondition.TAPPED_PERMANENT ->
            target is Target.Permanent &&
                state.sharedZones.battlefield
                    .firstOrNull { it.id == target.id }
                    ?.tapped == true
    }

/**
 * The targets a *legality* gate prices [definition] against (CR 601.2f) — every target the cast could
 * legally choose right now, or the empty list for a card with no target-conditional reduction. Additive
 * (`FW-TGTCOND`).
 *
 * **This is the "cheapest achievable cost" input, and it is exact rather than optimistic.** A
 * [CostReduction.IfTargets] applies when *at least one* chosen target satisfies its condition, so handing
 * it every candidate makes it apply exactly when some legal choice would make it apply — the cheapest
 * price any cast of the card can reach. Anything less would hide a legal play (ADR-005 in the direction
 * that shrinks the action space), and there is nothing cheaper to reach, so the gate cannot be over-
 * permissive either: the target request that follows keeps at least the cheapest-priced option
 * ([affordableTargetOptions]), so a cast admitted here always has somewhere to go.
 *
 * Empty for every card without such a reduction, which is all but one — so no existing cast pays for a
 * target enumeration it does not need.
 *
 * **Modal cards are refused loudly.** A modal card's targeting line belongs to its chosen mode
 * (CR 601.2b), which is not settled at a legality gate, so "every target it could choose" is not one
 * enumeration but one per mode. No card prints both, and guessing a mode here would price the cast against
 * a targeting line it may never adopt.
 */
internal fun cheapestTargetsFor(
    state: GameState,
    seat: PlayerId,
    definition: SpellDefinition,
    self: ObjectId,
): List<Target> {
    if (definition.costReduction !is CostReduction.IfTargets) return emptyList()
    require(definition.modes.isEmpty()) {
        "CR 601.2b/f: ${definition.characteristics.name} has both modes and a target-conditional cost " +
            "reduction; its cheapest cost depends on a mode that is not chosen yet (`FW-TGTCOND`)"
    }
    return legalTargets(state, definition.targetSpec, seat, Chooser.Spell(self))
}

/**
 * The subset of [options] a cast of [definition] by [seat] can still **afford** once that option is the
 * chosen target (CR 601.2c, CR 601.2f) — every option for a card with no target-conditional reduction, and
 * for one that has it, exactly the options whose resulting total cost has a payment plan. Additive
 * (`FW-TGTCOND`).
 *
 * **Why an affordability filter belongs in a targeting enumeration at all.** It normally does not, and no
 * other spec is filtered this way: which objects a spell may point at is a CR 115 question and has nothing
 * to do with mana. A target-conditional reduction is what makes the two questions meet — choosing an
 * untapped creature is what *prices* a Ride's End at `{4}{W}` — and the CR's own answer to "you chose a
 * target you then cannot pay for" is CR 601.2h plus the CR 728 rewind: the cast never happened. This engine
 * has no rewind of a gathering in progress, and offering an option whose only outcome is an unpayable
 * `ChoosePaymentPlan` with no options is ADR-005's enumerated-but-illegal defect. So the option is not
 * offered, exactly as the kicker announcement is not offered when the kicked cost is unaffordable
 * ([kickerAffordable]) — the same rule, applied to the other CR 601.2 choice that can price a cast.
 *
 * **The filter can never empty the list for a cast that was legally enumerated.** `castIsLegal` priced the
 * cast at the cheapest cost any legal target choice reaches ([cheapestTargetsFor]) and found a plan, so the
 * option that achieves that price is affordable and survives. That is a structural argument rather than a
 * board-by-board one, which is what it has to be.
 *
 * **Two reasons a choice can matter, one filter.** A target-conditional *reduction* is the card's own
 * declaration; a [dev.mtgplay.core.definition.StackTargetTax] is another spell's, live only while that
 * spell is on the stack (`W10-D`). Both make the total cost a function of the chosen target, so both are
 * filtered here — and the increase is the direction that makes the filter load-bearing rather than merely
 * tidy: without it a caster is offered a target they cannot pay for.
 *
 * **One target only.** With a maximum above one the cost would depend on the *set* chosen, so filtering per
 * option would be wrong in both directions — an option unaffordable alone may be affordable beside a
 * discounting sibling. That needs a subset enumeration, no card prints it, and the `require` says so rather
 * than letting the per-option test quietly answer a question it is not asked.
 *
 * Priced at the cheapest CR 601.2b announcement and the legality-time sacrifice reservation, because
 * neither is settled at the target stage (see `PendingCastRequest.kt`'s header for the gathering order).
 * Both are exact for the only card in the family, which prints no kicker, no X, and no additional cost.
 */
internal fun affordableTargetOptions(
    state: GameState,
    seat: PlayerId,
    subject: CastSubject,
    spec: TargetSpec,
    options: List<Target>,
): List<Target> {
    val definition = subject.definition
    // `W10-D`: the second reason a target choice can change what a cast costs — a spell on the stack
    // taxing spells that target it (CR 601.2f). Unlike the reduction, this one is a property of the
    // *board* rather than of the card, so the early-out asks both questions; see `StackTargetTax.kt`.
    if (definition.costReduction !is CostReduction.IfTargets && !taxedPricingApplies(state, definition)) {
        return options
    }
    require(spec.count.maximum <= 1) {
        "CR 601.2c/f: ${definition.characteristics.name} chooses up to ${spec.count.maximum} targets and " +
            "prices itself off them; affording that needs a subset enumeration, not a per-option test " +
            "(`FW-TGTCOND`)"
    }
    val reserved = minimalSacrificeReservation(state, seat, definition)
    return options.filter { option ->
        enumeratePaymentPlans(
            state,
            seat,
            totalCost(state, seat, subject.copy(targets = listOf(option))),
            reserved,
        ).isNotEmpty()
    }
}
