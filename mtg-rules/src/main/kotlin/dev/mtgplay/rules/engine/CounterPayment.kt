package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.CounterUnlessPaid
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingCounterPayment
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.effect.counterSpellById

/*
 * The "counter target spell **unless its controller pays** {N}" resolution flow (CR 701.5, CR 118.3a) —
 * Force Spike, Spell Pierce. Orchestrate → request → apply, the trio `ResolutionDiscard.kt` established.
 *
 * Three rules facts shape it, and each is a test:
 * - **The decider is the targeted spell's controller**, not the resolving counter's (CR 118.3a). This is
 *   the first decision in the engine made by someone other than the resolving object's controller.
 * - **It is not a cast and grants nobody priority.** Mana abilities may be activated for it because a
 *   resolving spell asked (CR 605.3b) and they resolve immediately without the stack (CR 605.3a), which
 *   is exactly what a [PaymentPlan] already executes; the counter's controller gets no window to respond.
 * - **Declining and being unable to pay are the same answer** (CR 118.3a): the spell is countered. Paying
 *   makes the counter resolve having done nothing, and it leaves the stack as a *resolved* spell — so the
 *   log says `SpellResolved`, never `SpellCountered`, on that branch.
 *
 * The CR 608.2b re-check runs *before* this flow is entered (`StackResolution.resolveSpell`), so a counter
 * whose target has already become illegal fizzles and **nobody is ever asked to pay**. Spell Pierce, being
 * restricted *and* unless-pay, is the card that makes that ordering observable.
 */

/**
 * Runs a resolving counter's [clause] (CR 118.3a): pauses for the targeted spell's controller to pay
 * [CounterUnlessPaid.cost], with the counter still on top of the stack and its victim still below it.
 *
 * The target has already survived the CR 608.2b re-check, so it is still on the stack; a definition that
 * carries this clause without a [dev.mtgplay.core.definition.TargetSpec.SpellOnStack] spec is a card
 * defect and fails loudly rather than silently doing nothing.
 */
internal fun orchestrateCounterUnlessPaid(
    state: GameState,
    entry: StackEntry.Spell,
    clause: CounterUnlessPaid,
): AdvanceResult {
    val target =
        entry.targets.singleOrNull() as? Target.SpellOnStack
            ?: error(
                "CR 118.3a: ${entry.obj.card.name} counters unless paid but targets ${entry.targets}, " +
                    "which is not a single spell on the stack",
            )
    val countered =
        spellOnStack(state, target.id)
            ?: error("CR 608.2b: ${entry.obj.card.name}'s target ${target.id} left the stack after the re-check")
    val paused =
        state.copy(
            pendingCounterPayment =
                PendingCounterPayment(
                    decider = countered.controller,
                    cost = clause.cost,
                    counteredObjectId = target.id,
                ),
        )
    return AdvanceResult.NeedsDecision(paused, pendingCounterPaymentRequest(paused))
}

/**
 * The unless-pay request the open [GameState.pendingCounterPayment] is waiting on (CR 118.3a). Pure per
 * ADR-004: declining at index 0, then one option per distinct payment plan the decider can afford right
 * now — none, when they cannot pay, which leaves a decline-only request that is still surfaced.
 */
internal fun pendingCounterPaymentRequest(state: GameState): DecisionRequest.ChooseCounterPayment {
    val pending = state.pendingCounterPayment ?: error("no counter payment is pending")
    val countered =
        spellOnStack(state, pending.counteredObjectId)
            ?: error("CR 118.3a: the spell ${pending.counteredObjectId} an unless-pay clause names is not on the stack")
    val plans = enumeratePaymentPlans(state, pending.decider, pending.cost)
    return DecisionRequest.ChooseCounterPayment(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        card = countered.obj.card,
        cost = pending.cost,
        options =
            listOf(DecisionRequest.ChooseCounterPayment.Option.Decline) +
                plans.map { DecisionRequest.ChooseCounterPayment.Option.Pay(it) },
    )
}

/**
 * Applies the unless-pay answer (CR 118.3a), then finishes the counter's own resolution (CR 608.2m).
 *
 * [plan] is `null` for a decline, in which case the targeted spell is countered (CR 701.5a) and the
 * counter leaves the stack as a **resolved** spell — countering is what it did, not what happened to it.
 * A non-null plan pays in full through the shared [payManaPlan] executor, the spell is saved, and the
 * counter resolves having done nothing at all.
 */
internal fun applyCounterPayment(
    state: GameState,
    plan: PaymentPlan?,
): AdvanceResult {
    val pending = state.pendingCounterPayment ?: error("no counter payment is pending")
    val entry =
        state.sharedZones.stack.lastOrNull() as? StackEntry.Spell
            ?: error("CR 608.1: an unless-pay payment requires the resolving counter on top of the stack")
    val cleared = state.copy(pendingCounterPayment = null)
    val settled =
        if (plan == null) {
            counterSpellById(cleared, pending.counteredObjectId, counteredBy = entry.obj.id)
        } else {
            payManaPlan(cleared, pending.decider, pending.cost, plan)
        }
    return completeInstantSorceryResolution(settled, entry)
}
