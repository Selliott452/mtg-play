package dev.mtgplay.rules.engine

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingCast
import dev.mtgplay.rules.decision.ManaSourceChoice
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.decision.SymbolPayment

/*
 * The cost-payment stages shared by the CR 601 cast pipeline, the plot special action, and activated
 * abilities (CR 601.2g–h, CR 602.2b, CR 702.140): the mana payment executor and the two P6.2a non-mana
 * cast-cost stages. Split from CastingPipeline.kt so each file stays within its function budget.
 */

/**
 * Executes the chosen [plan] to pay [cost] for [seat] (CR 601.2g–h, docs/design/mana-payment.md): each
 * `ByTapping` payment resolves a tap-for-mana ability immediately (no stack, no priority — CR 605.3),
 * pooled mana pays from the pool, and `WithTwoLife` pays the Phyrexian alternative. Shared by casting,
 * the plot special action (CR 702.140), and activated abilities (CR 602.2g). The plan is validated
 * against the cost first; enumeration guarantees it fits (ADR-005), so a mismatch is an engine defect.
 */
internal fun payManaPlan(
    state: GameState,
    seat: PlayerId,
    cost: ManaCost,
    plan: PaymentPlan,
): GameState {
    validatePlanShape(cost, plan)
    return plan.payments.fold(state) { current, payment ->
        when (payment) {
            is SymbolPayment.WithMana ->
                when (val source = payment.source) {
                    ManaSourceChoice.FromPool ->
                        removeManaFromPool(current, seat, payment.mana)
                    is ManaSourceChoice.ByTapping -> {
                        val produced = resolveTapForMana(current, seat, source.sourceClass, payment.mana)
                        removeManaFromPool(produced, seat, payment.mana)
                    }
                }
            SymbolPayment.WithTwoLife ->
                changeLife(current, seat, -PHYREXIAN_LIFE_COST)
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
