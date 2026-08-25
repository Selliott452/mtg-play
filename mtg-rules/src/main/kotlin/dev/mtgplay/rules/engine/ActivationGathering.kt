package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingActivation
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.decision.PaymentPlan
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/*
 * The **gathering** half of general activated abilities (CR 602.2): choosing to activate opens a
 * [PendingActivation] and the engine suspends once per choice the activation needs, each re-derivable
 * from the paused state alone (ADR-004). Split from Activation.kt (enumeration) and
 * ActivationExecution.kt (payment, stack placement, resolution) so each file stays within its function
 * budget.
 *
 * **The order is CR 601.2b–i**, which CR 602.2b defers to wholesale: targets (CR 601.2c) first, then the
 * "discard a card" cost selection, then the payment plan (CR 602.2f–g) — the same order the cast
 * pipeline runs. Nothing about the game changes while gathering; the whole activation executes
 * atomically in the transition that receives the last choice.
 */

/** Whether [ability]'s cost includes a "discard a card" component. */
private fun hasDiscardACard(ability: ActivatedAbility): Boolean = ability.cost.any { it == AbilityCost.DiscardACard }

/**
 * Begins activating the [abilityIndex]th ability of [sourceObjectId] for [seat] (CR 602.2): opens a
 * [PendingActivation] and gathers what the activation needs, in the CR 601.2b–i order — the targets
 * (CR 601.2c), the "discard a card" selection, then the payment plan — or executes immediately when it
 * needs none of them. Legality was checked at enumeration (ADR-005), targets included.
 */
internal fun beginActivation(
    state: GameState,
    seat: PlayerId,
    sourceObjectId: ObjectId,
    scope: AbilityZoneScope,
    abilityIndex: Int,
): AdvanceResult {
    val ability = abilityAt(state, seat, sourceObjectId, scope, abilityIndex)
    val opened =
        state.copy(
            pendingActivation =
                PendingActivation(
                    activator = seat,
                    sourceObjectId = sourceObjectId,
                    source = scope,
                    abilityIndex = abilityIndex,
                    chosenDiscard = if (hasDiscardACard(ability)) null else persistentListOf(),
                    // CR 601.2c: an untargeted ability — and an "up to N" one with nothing legal to
                    // point at — settles its (empty) target list immediately.
                    chosenTargets =
                        if (targetChoiceIsVacuous(state, ability.targetSpec, seat, self = null)) {
                            persistentListOf()
                        } else {
                            null
                        },
                    // CR 602.1: a "Sacrifice an artifact" component needs a selection; every other
                    // activation settles it empty.
                    chosenSacrifice = if (sacrificeComponent(ability) != null) null else persistentListOf(),
                    // CR 602.1: likewise a "Return a Forest you control to its owner's hand" component.
                    chosenReturn = if (returnComponent(ability) != null) null else persistentListOf(),
                ),
        )
    return advanceActivationGathering(opened)
}

/** Surfaces the next activation decision (targets, discard, then payment), or executes when none remain. */
private fun advanceActivationGathering(state: GameState): AdvanceResult {
    val pending = state.pendingActivation ?: error("no activation is gathering costs")
    val ability = abilityAt(state, pending.activator, pending.sourceObjectId, pending.source, pending.abilityIndex)
    return when {
        pending.chosenTargets == null -> AdvanceResult.NeedsDecision(state, pendingActivationRequest(state))
        pending.chosenDiscard == null -> AdvanceResult.NeedsDecision(state, pendingActivationRequest(state))
        pending.chosenSacrifice == null -> AdvanceResult.NeedsDecision(state, pendingActivationRequest(state))
        pending.chosenReturn == null -> AdvanceResult.NeedsDecision(state, pendingActivationRequest(state))
        manaComponent(ability) != null -> AdvanceResult.NeedsDecision(state, pendingActivationRequest(state))
        // No further decisions: pay the cost (with an empty plan) and put the ability on the stack.
        else -> executeActivation(state, PaymentPlan(emptyList(), emptyList()))
    }
}

/**
 * The request the open [GameState.pendingActivation] is waiting on (CR 602.2b, running CR 601.2b–i):
 * the target choice first (CR 601.2c), then the "discard a card" selection, then the payment plan for
 * the mana component. A pure function of the state (ADR-004).
 */
internal fun pendingActivationRequest(state: GameState): DecisionRequest {
    val pending = state.pendingActivation ?: error("no activation is gathering costs")
    val source =
        activationSource(state, pending.activator, pending.source, pending.sourceObjectId)
            ?: error("CR 602.2: the activation source ${pending.sourceObjectId} is not in its zone ${pending.source}")
    val ability = abilityAt(state, pending.activator, pending.sourceObjectId, pending.source, pending.abilityIndex)
    val id = DecisionRequestId(pending.activator, state.player(pending.activator).decisionsAnswered)
    return when {
        pending.chosenTargets == null ->
            targetRequest(
                id = id,
                cardObjectId = pending.sourceObjectId,
                card = source.card,
                spec = ability.targetSpec,
                options = legalTargets(state, ability.targetSpec, pending.activator, self = null),
            )
        pending.chosenDiscard == null ->
            DecisionRequest.ChooseAbilityDiscard(
                id = id,
                sourceObjectId = pending.sourceObjectId,
                card = source.card,
                options =
                    discardableForAbility(state, pending.activator, source, pending.source)
                        .map { DecisionRequest.ChooseAbilityDiscard.Option(it.id, it.card) },
                count = 1,
            )
        // CR 602.1 with CR 701.17: the "Sacrifice an artifact" selection, offering exactly the
        // candidates the legality check counted ([abilitySacrificeCandidates]) — an option that left
        // the sibling mana component unpayable would dead-end the activation (ADR-005).
        pending.chosenSacrifice == null ->
            DecisionRequest.ChooseAbilitySacrifice(
                id = id,
                sourceObjectId = pending.sourceObjectId,
                card = source.card,
                options =
                    abilitySacrificeCandidates(state, pending.activator, source, ability)
                        .map { DecisionRequest.ChooseAbilitySacrifice.Option(it.id, it.card) },
                count = 1,
            )
        // CR 602.1 with CR 701.4a: the "Return a Forest you control" selection, offering exactly the
        // candidates the legality check counted ([abilityReturnCandidates]).
        pending.chosenReturn == null ->
            DecisionRequest.ChooseAbilityReturn(
                id = id,
                sourceObjectId = pending.sourceObjectId,
                card = source.card,
                options =
                    abilityReturnCandidates(state, pending.activator, source, ability)
                        .map { DecisionRequest.ChooseAbilityReturn.Option(it.id, it.card) },
                count = 1,
            )
        else -> activationPaymentRequest(state, pending, source, ability, id)
    }
}

/**
 * The payment-plan request of an activation whose every cost selection is settled (CR 602.2f–g). Split
 * out of [pendingActivationRequest] to keep that dispatch inside detekt's length budget.
 */
private fun activationPaymentRequest(
    state: GameState,
    pending: PendingActivation,
    source: GameObject,
    ability: ActivatedAbility,
    id: DecisionRequestId,
): DecisionRequest.ChoosePaymentPlan {
    val mana = manaComponent(ability) ?: error("CR 602.2g: a payment request requires a mana cost component")
    return DecisionRequest.ChoosePaymentPlan(
        id = id,
        cardObjectId = pending.sourceObjectId,
        card = source.card,
        // CR 602.2f: an activated ability's mana cost is modifiable in general, but nothing in the pool
        // modifies one and no declaration can express it (docs/design/cost-modification.md §12) — so the
        // determined cost *is* the printed component, and saying so here keeps the request's cost field
        // meaning the same thing it means for a spell.
        cost = mana.cost,
        // Same reservation the legality check used (triage trap T17): the options offered must be
        // exactly the ones execution can carry out (ADR-005), so the source cannot appear here as a
        // payer for a cost that also taps or sacrifices it — nor may the permanent already chosen for a
        // sacrifice component when that permanent produces mana *by* being sacrificed, nor the one
        // chosen for a return component at all (docs/design/mana-payment.md §2.2, §2.4).
        options =
            enumeratePaymentPlans(
                state,
                pending.activator,
                mana.cost,
                manaSourcesReservedBy(
                    state,
                    source,
                    ability,
                    pending.chosenSacrifice.orEmpty(),
                    pending.chosenReturn.orEmpty(),
                ),
            ),
    )
}

/**
 * Records the permanent chosen to pay an [AbilityCost.Sacrifice] component on the open activation
 * (CR 602.1) and continues gathering. It is sacrificed only when the activation executes
 * (CR 602.2b), atomically with everything else — nothing has left the battlefield yet.
 */
internal fun applyChosenAbilitySacrifice(
    state: GameState,
    sacrificeObjectIds: List<ObjectId>,
): AdvanceResult {
    val pending = state.pendingActivation ?: error("no activation is gathering costs")
    require(pending.chosenSacrifice == null) { "CR 602.2b: this activation's sacrifice cost is already chosen" }
    return advanceActivationGathering(
        state.copy(pendingActivation = pending.copy(chosenSacrifice = sacrificeObjectIds.toPersistentList())),
    )
}

/**
 * Records the permanent chosen to pay an [AbilityCost.ReturnPermanentYouControl] component on the open
 * activation (CR 602.1) and continues gathering. It is returned to its owner's hand only when the
 * activation executes (CR 602.2b), atomically with everything else — nothing has left the battlefield
 * yet, which is what lets the payment plan enumerated next reserve it.
 */
internal fun applyChosenAbilityReturn(
    state: GameState,
    returnObjectIds: List<ObjectId>,
): AdvanceResult {
    val pending = state.pendingActivation ?: error("no activation is gathering costs")
    require(pending.chosenReturn == null) { "CR 602.2b: this activation's return cost is already chosen" }
    return advanceActivationGathering(
        state.copy(pendingActivation = pending.copy(chosenReturn = returnObjectIds.toPersistentList())),
    )
}

/** Records the chosen "discard a card" cost card on the open activation and continues gathering. */
internal fun applyChosenAbilityDiscard(
    state: GameState,
    discardObjectIds: List<ObjectId>,
): AdvanceResult {
    val pending = state.pendingActivation ?: error("no activation is gathering costs")
    require(pending.chosenDiscard == null) { "CR 602.2b: this activation's discard cost is already chosen" }
    return advanceActivationGathering(
        state.copy(pendingActivation = pending.copy(chosenDiscard = discardObjectIds.toPersistentList())),
    )
}

/**
 * Records the chosen [targets] on the open activation (CR 602.2b, following CR 601.2c) and continues
 * gathering. A list since `FW-MULTITGT`: Faerie Macabre's and Blood Fountain's "up to two" abilities
 * settle here with nought, one, or two, and every earlier targeted ability with exactly one.
 */
internal fun applyChosenActivationTarget(
    state: GameState,
    targets: List<Target>,
): AdvanceResult {
    val pending = state.pendingActivation ?: error("no activation is gathering costs")
    require(pending.chosenTargets == null) { "CR 601.2c: this activation's targets are already chosen" }
    return advanceActivationGathering(
        state.copy(pendingActivation = pending.copy(chosenTargets = targets.toPersistentList())),
    )
}
