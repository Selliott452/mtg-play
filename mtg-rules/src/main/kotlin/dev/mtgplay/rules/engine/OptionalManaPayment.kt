package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.OptionalManaThenDraw
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingOptionalManaPayment
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.resolutionController
import dev.mtgplay.core.state.resolutionSourceCard
import dev.mtgplay.core.state.resolutionSourceId
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.effect.drawCards

/*
 * The optional "you may pay {cost}; if you do, draw N" clause (CR 601.3b) — Nihil Spellbomb's
 * "When this artifact is put into a graveyard from the battlefield, you may pay {B}. If you do, draw a
 * card." Additive (`W8-D`), a member of the `FW-CLAUSEHOOK` family
 * (docs/design/resolution-clause-hook.md): orchestrate → request → apply.
 *
 * **It is `FW-COUNTER`'s payment flow with a different decider and a different consequence**, and the
 * two are written as siblings rather than one parameterised flow because their rules come from different
 * sentences. CR 118.3a demands a counter's payment of the *targeted spell's* controller and counters
 * that spell on a decline; "you may pay" (CR 601.3b) addresses the resolving object's own controller,
 * and a decline merely skips the draw. What they legitimately share is the *enumeration*:
 * [enumeratePaymentPlans] and [payManaPlan], the same functions a cast uses, so a seat's affordable
 * plans here and at a cast can never disagree.
 *
 * **The clause's source is normally already gone when the request is built.** Nihil Spellbomb offers the
 * payment from a trigger that fires as the artifact is put into a graveyard (CR 603.6b), so by the time
 * the ability resolves its source is a card in a graveyard, not a permanent. The request therefore
 * carries a printed [dev.mtgplay.core.identity.CardRef] taken from the trigger's last-known information
 * (CR 113.7c) rather than looking anything up on the battlefield.
 *
 * **Declining and being unable to pay are the same answer**, so a seat that can afford nothing is still
 * asked — a decline-only request. Making the pause conditional on affordability would put a
 * state-dependent hole in the decision sequence, which is exactly what a canonical replay log cannot
 * have (ADR-004).
 */

/**
 * Runs the optional-mana-then-draw clause of the resolving [entry] (CR 601.3b): pauses for its
 * controller to pay or decline. Called by the clause hook after the object's ordinary effect, while it
 * is still on top of the stack.
 */
internal fun orchestrateOptionalManaThenDraw(
    state: GameState,
    entry: StackEntry,
    clause: OptionalManaThenDraw,
): AdvanceResult {
    val paused =
        state.copy(
            pendingOptionalManaPayment =
                PendingOptionalManaPayment(
                    decider = entry.resolutionController,
                    cost = clause.cost,
                    drawCount = clause.drawCount,
                    // CR 113.7c: the source as last known — for Nihil Spellbomb it is already a card in
                    // a graveyard, so this is genuinely last-known information rather than a lookup.
                    sourceId = entry.resolutionSourceId,
                    sourceCard = entry.resolutionSourceCard,
                ),
        )
    return AdvanceResult.NeedsDecision(paused, pendingOptionalManaPaymentRequest(paused))
}

/**
 * The pay-or-decline request the open [GameState.pendingOptionalManaPayment] is waiting on (CR 601.3b).
 * Pure per ADR-004: declining at index 0, then one option per distinct payment plan the decider can
 * afford right now — none, when they cannot pay, which leaves a decline-only request that is still
 * surfaced.
 */
internal fun pendingOptionalManaPaymentRequest(state: GameState): DecisionRequest.ChooseOptionalManaPayment {
    val pending = state.pendingOptionalManaPayment ?: error("no optional mana payment is pending")
    val plans = enumeratePaymentPlans(state, pending.decider, pending.cost)
    return DecisionRequest.ChooseOptionalManaPayment(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        sourceCard = pending.sourceCard,
        cost = pending.cost,
        drawCount = pending.drawCount,
        options =
            listOf(DecisionRequest.ChooseOptionalManaPayment.Option.Decline) +
                plans.map { DecisionRequest.ChooseOptionalManaPayment.Option.Pay(it) },
    )
}

/**
 * Applies the pay-or-decline answer (CR 601.3b), then finishes the resolving object through the shared
 * [completeClauseResolution] — a spell's CR 608.2m graveyard move or an ability's CR 113.7a cessation.
 *
 * [plan] is `null` for a decline, in which case nothing is paid and nothing is drawn. A non-null plan
 * pays in full through the shared [payManaPlan] executor **before** the draw, which is the printed order
 * ("you may pay {B}. **If you do**, draw a card") and matters: the mana is spent whether or not the draw
 * can be completed from an empty library.
 */
internal fun applyOptionalManaPayment(
    state: GameState,
    plan: PaymentPlan?,
): AdvanceResult {
    val pending = state.pendingOptionalManaPayment ?: error("no optional mana payment is pending")
    val entry = resolvingClauseEntry(state)
    val cleared = state.copy(pendingOptionalManaPayment = null)
    val settled =
        if (plan == null) {
            cleared
        } else {
            drawCards(payManaPlan(cleared, pending.decider, pending.cost, plan), pending.decider, pending.drawCount)
        }
    return completeClauseResolution(settled, entry)
}
