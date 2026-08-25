package dev.mtgplay.rules.engine

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingCast
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.decision.SymbolPayment

/*
 * The cost-payment stages shared by the CR 601 cast pipeline, the plot special action, and activated
 * abilities (CR 601.2g–h, CR 602.2b, CR 702.140): the mana payment executor and the two P6.2a non-mana
 * cast-cost stages. Split from CastingPipeline.kt so each file stays within its function budget.
 */

/**
 * Executes the chosen [plan] to pay [cost] for [seat] (CR 601.2g–h, docs/design/mana-payment.md), in
 * the CR's own two steps and in that order:
 *
 * 1. **CR 601.2g** — every [PaymentPlan.activations] entry activates the first usable member of its
 *    source class, resolving the mana ability immediately (no stack, no priority — CR 605.3) and
 *    adding its mana, and any CR 605.1b triggered bonus mana, to the pool.
 * 2. **CR 601.2h** — every [PaymentPlan.payments] entry then removes one mana of its type from the
 *    pool, or pays the Phyrexian 2-life alternative (CR 107.4).
 *
 * All production precedes all payment, which is both what the CR prescribes and what lets one
 * activation pay several symbols. Whatever the plan produces and does not spend floats until the
 * step ends (CR 500.4). Shared by casting, the plot special action (CR 702.140), and activated
 * abilities (CR 602.2g). The plan is validated against the cost first; enumeration guarantees it
 * fits (ADR-005), so a mismatch is an engine defect.
 */
internal fun payManaPlan(
    state: GameState,
    seat: PlayerId,
    cost: ManaCost,
    plan: PaymentPlan,
): GameState {
    validatePlanShape(cost, plan)
    // CR 601.2g: the activations are a multiset, not a schedule. Since `FW-MANACOST` an activation may
    // cost mana, so an order has to be *derived* — by the same function the enumerator used to decide
    // the plan was feasible at all, which is what stops the two halves from disagreeing
    // (docs/design/mana-payment.md §11.2).
    val order =
        manaActivationOrder(
            state
                .player(seat)
                .manaPool
                .groupingBy { it }
                .eachCount(),
            plan.activations,
        )
            ?: error(
                "CR 601.2g: no order of ${plan.activations.size} activations pays for itself; the plan " +
                    "was enumerated against this state, so this is an engine defect",
            )
    val produced =
        order.foldIndexed(state) { position, current, activation ->
            resolveManaActivation(
                current,
                seat,
                plan.activations[activation],
                remaining = order.drop(position + 1).map(plan.activations::get),
            )
        }
    return plan.payments.fold(produced) { current, payment ->
        when (payment) {
            is SymbolPayment.WithMana -> removeManaFromPool(current, seat, payment.mana)
            SymbolPayment.WithTwoLife -> changeLife(current, seat, -PHYREXIAN_LIFE_COST)
        }
    }
}

/**
 * Stage CR 601.2h — non-mana sacrifice cost: sacrifices the permanents chosen for a sacrifice cost
 * (Fireblast's two Mountains, Lava Dart's Mountain) to their owners' graveyards (CR 701.17,
 * [sacrificePermanents]). A no-op when the permission has no such cost (the settled list is empty). The
 * permanents were chosen legally while gathering (ADR-005), so a missing one is an engine defect.
 */
internal fun paySacrificeCosts(
    state: GameState,
    cast: PendingCast,
): GameState {
    val toSacrifice =
        cast.sacrificeCost
            ?: error("CR 601.2h: the sacrifice cost of ${cast.cardObjectId} was not settled before payment")
    return sacrificePermanents(state, cast.caster, toSacrifice)
}

/**
 * Stage CR 601.2b — additional discard cost: discards the cards chosen for an additional discard cost
 * (Grab the Prize's "discard a card") from the caster's hand, **through the CR 614/616 replacement
 * framework** ([discardApplyingReplacements]) so a discarded card with madness is exiled instead
 * (CR 702.35a) and its reflexive cast trigger fires — the Madness deck's core synergy. A no-op when the
 * definition has no such cost (the settled list is empty). The cards were chosen legally while gathering
 * (ADR-005), so a missing one is an engine defect.
 */
internal fun payAdditionalDiscardCost(
    state: GameState,
    cast: PendingCast,
): GameState {
    val toDiscard =
        cast.additionalDiscard
            ?: error("CR 601.2b: the additional discard cost of ${cast.cardObjectId} was not settled before payment")
    return toDiscard.fold(state) { current, discardId -> discardApplyingReplacements(current, cast.caster, discardId) }
}

/**
 * Stage CR 601.2h — **intrinsic** sacrifice additional cost: sacrifices the permanents chosen for a
 * card's own "As an additional cost to cast this spell, sacrifice …" (Eviscerator's Insight, Reckoner's
 * Bargain) to their owners' graveyards (CR 701.17, [sacrificePermanents]). A no-op when the definition
 * has no such cost (the settled list is empty).
 *
 * **Run after the mana payment**, unlike [paySacrificeCosts]. CR 601.2g (activate mana abilities)
 * precedes CR 601.2h (pay costs), so a land tapped for mana may then be sacrificed to this cost — the
 * plan that does so is deliberately enumerable, and paying the sacrifice first would make it throw. The
 * one permanent that genuinely cannot do both is a sacrifice-cost mana source, and *that* one is
 * reserved out of the payment enumeration by object (docs/design/mana-payment.md §2.2). Both payments
 * happen inside the same atomic transition, so no player can observe the order.
 *
 * The permanents were chosen legally while gathering (ADR-005), so a missing one is an engine defect
 * and [sacrificePermanents] fails loudly.
 */
internal fun payAdditionalSacrificeCost(
    state: GameState,
    cast: PendingCast,
): GameState {
    val toSacrifice =
        cast.additionalSacrifice
            ?: error("CR 601.2b: the additional sacrifice cost of ${cast.cardObjectId} was not settled before payment")
    return sacrificePermanents(state, cast.caster, toSacrifice)
}
