package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.CounterUnlessPaid
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingCounterPayment
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.resolutionController
import dev.mtgplay.core.state.resolutionSourceCard
import dev.mtgplay.core.state.stackObjectId
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.effect.counterStackObjectById

/*
 * The "counter it **unless its controller pays** {N}" resolution flow (CR 701.5, CR 118.3a, CR 702.21a) —
 * Force Spike and Spell Pierce on the spell side, ward on the ability side. Orchestrate → request → apply,
 * the trio `ResolutionDiscard.kt` established.
 *
 * Three rules facts shape it, and each is a test:
 * - **The decider is the countered object's controller**, not the resolving counter's (CR 118.3a). This is
 *   the first decision in the engine made by someone other than the resolving object's controller.
 * - **It is not a cast and grants nobody priority.** Mana abilities may be activated for it because a
 *   resolving spell asked (CR 605.3b) and they resolve immediately without the stack (CR 605.3a), which
 *   is exactly what a [PaymentPlan] already executes; the counter's controller gets no window to respond.
 * - **Declining and being unable to pay are the same answer** (CR 118.3a): the object is countered. Paying
 *   makes the counter resolve having done nothing, and it leaves the stack as a *resolved* object — so the
 *   log says `SpellResolved`/`TriggeredAbilityResolved`, never `SpellCountered`, on that branch.
 *
 * **Two carriers, and the difference is how the victim is named** (`FW-WARD`):
 * - a **spell** with [CounterUnlessPaid] names its victim as its single
 *   [Target.SpellOnStack] target. The CR 608.2b re-check runs *before* this flow is entered
 *   (`StackResolution.resolveSpell`), so a counter whose target has already become illegal fizzles and
 *   **nobody is ever asked to pay**; a target that has left the stack outright is therefore an engine
 *   defect here.
 * - a **ward trigger** names its victim as the [dev.mtgplay.core.state.PendingTrigger.targetedBy] it
 *   captured when it fired. That is linked information, not a target, so it gets no re-check — and the
 *   object may legally have resolved or been countered since. Ward then counters nothing and asks nobody
 *   to pay, which is the printed outcome rather than a special case.
 */

/**
 * Runs a resolving object's [clause] (CR 118.3a, CR 702.21a): pauses for the countered object's controller
 * to pay [CounterUnlessPaid.cost], with the resolving object still on top of the stack and its victim
 * still below it.
 *
 * Returns `null` when there is nothing to counter — a ward trigger whose victim has left the stack — which
 * the caller turns into an ordinary resolution.
 */
internal fun orchestrateCounterUnlessPaid(
    state: GameState,
    entry: StackEntry,
    clause: CounterUnlessPaid,
): AdvanceResult? {
    val victimId = counterUnlessPaidVictim(entry)
    val countered = stackObjectOnStack(state, victimId)
    if (countered == null) {
        // A *spell* named its victim as a target, so CR 608.2b re-checked it moments ago: a victim that
        // has gone is an engine defect. A ward trigger named its victim as linked information and got no
        // re-check, so a victim that has gone is the printed outcome.
        check(entry !is StackEntry.Spell) {
            "CR 608.2b: ${entry.resolutionSourceCard.name}'s target $victimId left the stack after the re-check"
        }
        return null
    }
    val paused =
        state.copy(
            pendingCounterPayment =
                PendingCounterPayment(
                    decider = countered.resolutionController,
                    cost = clause.cost,
                    counteredObjectId = victimId,
                ),
        )
    return AdvanceResult.NeedsDecision(paused, pendingCounterPaymentRequest(paused))
}

/**
 * The stack object a resolving [entry]'s unless-pay clause counters (CR 118.3a, CR 702.21a) — its single
 * spell target for a counterspell, its captured linked information for a ward trigger.
 *
 * An activated ability cannot carry the clause: nothing in the pool prints one, and it would need to say
 * which of its targets was the victim. A loud gate rather than a silent choice.
 */
private fun counterUnlessPaidVictim(entry: StackEntry): ObjectId =
    when (entry) {
        is StackEntry.Spell ->
            (entry.targets.singleOrNull() as? Target.SpellOnStack)?.id
                ?: error(
                    "CR 118.3a: ${entry.obj.card.name} counters unless paid but targets ${entry.targets}, " +
                        "which is not a single spell on the stack",
                )
        is StackEntry.Ability ->
            entry.trigger.targetedBy
                ?: error(
                    "CR 702.21a: ${entry.trigger.sourceCard.name}'s trigger counters unless paid but " +
                        "captured no targeting object when it fired",
                )
        is StackEntry.ActivatedAbilityOnStack ->
            error(
                "CR 118.3a: ${entry.sourceCard.name}'s activated ability declares an unless-pay clause, " +
                    "which no rule in this engine tells it which object to counter",
            )
    }

/**
 * The unless-pay request the open [GameState.pendingCounterPayment] is waiting on (CR 118.3a). Pure per
 * ADR-004: declining at index 0, then one option per distinct payment plan the decider can afford right
 * now — none, when they cannot pay, which leaves a decline-only request that is still surfaced.
 */
internal fun pendingCounterPaymentRequest(state: GameState): DecisionRequest.ChooseCounterPayment {
    val pending = state.pendingCounterPayment ?: error("no counter payment is pending")
    val countered =
        stackObjectOnStack(state, pending.counteredObjectId)
            ?: error(
                "CR 118.3a: the object ${pending.counteredObjectId} an unless-pay clause names " +
                    "is not on the stack",
            )
    val plans = enumeratePaymentPlans(state, pending.decider, pending.cost)
    return DecisionRequest.ChooseCounterPayment(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        // For an ability this is the printed identity of its source (CR 113.7c): an ability is not a
        // card, so the seat is told which permanent's ability is about to be countered.
        card = countered.resolutionSourceCard,
        cost = pending.cost,
        options =
            listOf(DecisionRequest.ChooseCounterPayment.Option.Decline) +
                plans.map { DecisionRequest.ChooseCounterPayment.Option.Pay(it) },
    )
}

/**
 * Applies the unless-pay answer (CR 118.3a), then finishes the resolving object's own resolution
 * (CR 608.2m for a spell, CR 113.7a for an ability).
 *
 * [plan] is `null` for a decline, in which case the countered object is countered (CR 701.5a) and the
 * resolving object leaves the stack as a **resolved** one — countering is what it did, not what happened
 * to it. A non-null plan pays in full through the shared [payManaPlan], the object is saved, and the
 * counter resolves having done nothing at all.
 */
internal fun applyCounterPayment(
    state: GameState,
    plan: PaymentPlan?,
): AdvanceResult {
    val pending = state.pendingCounterPayment ?: error("no counter payment is pending")
    val entry =
        state.sharedZones.stack.lastOrNull()
            ?: error("CR 608.1: an unless-pay payment requires the resolving object on top of the stack")
    val cleared = state.copy(pendingCounterPayment = null)
    val settled =
        if (plan == null) {
            // Every resolving object in a real game has a stack identity; only a hand-built fixture
            // entry lacks one, and the log has nothing to name it by.
            val counteringId =
                entry.stackObjectId
                    ?: error("CR 111.1: the resolving ${entry.resolutionSourceCard.name} has no stack identity")
            counterStackObjectById(cleared, pending.counteredObjectId, counteredBy = counteringId)
        } else {
            payManaPlan(cleared, pending.decider, pending.cost, plan)
        }
    return completeClauseResolution(settled, entry)
}
