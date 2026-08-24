package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingActivation
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.decision.PriorityOption
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/*
 * General activated abilities (CR 602): non-mana activated abilities on permanents (and, hand-scoped,
 * on cards in hand). Choosing to activate opens a [PendingActivation] and the engine gathers the cost's
 * selections — a card to discard, then a payment plan — before paying the whole composite cost
 * atomically (CR 602.2b) and putting the ability on the stack ([StackEntry.ActivatedAbilityOnStack]).
 * The ability then resolves like a triggered ability (AbilityResolution.kt), performing its effect and
 * ceasing to exist (CR 113.7a). Mana abilities (CR 605) are a separate, stackless path.
 */

/**
 * The activate-ability options for [seat] (CR 602.1, CR 117.1c): one [PriorityOption.ActivateAbility] per
 * (source, ability) the seat may activate right now (ADR-005). Scans [seat]'s battlefield permanents for
 * [AbilityZoneScope.Battlefield] abilities and their hand for [AbilityZoneScope.Hand] abilities; an
 * ability is offered only when its whole composite cost is payable ([abilityCostPayable]). Battlefield
 * order then hand order fixes the option order (ADR-006).
 */
internal fun activationOptions(
    state: GameState,
    seat: PlayerId,
): List<PriorityOption.ActivateAbility> =
    buildList {
        state.sharedZones.battlefield
            .filter { it.owner == seat }
            .forEach { source -> addAbilities(state, seat, source, AbilityZoneScope.Battlefield) }
        state
            .player(seat)
            .hand
            .forEach { source -> addAbilities(state, seat, source, AbilityZoneScope.Hand) }
    }

private fun MutableList<PriorityOption.ActivateAbility>.addAbilities(
    state: GameState,
    seat: PlayerId,
    source: GameObject,
    scope: AbilityZoneScope,
) {
    val abilities = state.definitions[source.card]?.activatedAbilities.orEmpty()
    abilities.forEachIndexed { index, ability ->
        if (ability.zoneScope == scope && abilityCostPayable(state, seat, source, scope, ability)) {
            add(PriorityOption.ActivateAbility(source.id, source.card, index, scope))
        }
    }
}

/** Whether every component of [ability]'s cost is payable by [seat] for [source] right now (CR 602.2). */
internal fun abilityCostPayable(
    state: GameState,
    seat: PlayerId,
    source: GameObject,
    scope: AbilityZoneScope,
    ability: ActivatedAbility,
): Boolean =
    ability.cost.all { component ->
        when (component) {
            is AbilityCost.Mana -> enumeratePaymentPlans(state, seat, component.cost).isNotEmpty()
            AbilityCost.TapSelf ->
                scope == AbilityZoneScope.Battlefield &&
                    !source.tapped &&
                    !(isCreature(state, source) && source.summoningSick)
            AbilityCost.SacrificeSelf -> scope == AbilityZoneScope.Battlefield
            AbilityCost.DiscardSelf -> scope == AbilityZoneScope.Hand
            AbilityCost.DiscardACard -> discardableForAbility(state, seat, source, scope).isNotEmpty()
        }
    }

/** The hand cards [seat] may discard to a "discard a card" cost — every hand card but a hand source itself. */
private fun discardableForAbility(
    state: GameState,
    seat: PlayerId,
    source: GameObject,
    scope: AbilityZoneScope,
): List<GameObject> = state.player(seat).hand.filter { !(scope == AbilityZoneScope.Hand && it.id == source.id) }

/** The mana cost component of [ability], or `null` if it has none. */
private fun manaComponent(ability: ActivatedAbility): AbilityCost.Mana? =
    ability.cost.filterIsInstance<AbilityCost.Mana>().singleOrNull()

/** Whether [ability]'s cost includes a "discard a card" component. */
private fun hasDiscardACard(ability: ActivatedAbility): Boolean = ability.cost.any { it == AbilityCost.DiscardACard }

/**
 * Begins activating the [abilityIndex]th ability of [sourceObjectId] for [seat] (CR 602.2): opens a
 * [PendingActivation] and gathers its cost — the "discard a card" selection first, then the payment plan
 * — or executes immediately when the cost needs neither. Legality was checked at enumeration (ADR-005).
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
                ),
        )
    return advanceActivationGathering(opened)
}

/** Surfaces the next activation decision (discard, then payment), or executes when none remain. */
private fun advanceActivationGathering(state: GameState): AdvanceResult {
    val pending = state.pendingActivation ?: error("no activation is gathering costs")
    val ability = abilityAt(state, pending.activator, pending.sourceObjectId, pending.source, pending.abilityIndex)
    return when {
        pending.chosenDiscard == null -> AdvanceResult.NeedsDecision(state, pendingActivationRequest(state))
        manaComponent(ability) != null -> AdvanceResult.NeedsDecision(state, pendingActivationRequest(state))
        // No further decisions: pay the cost (with an empty plan) and put the ability on the stack.
        else -> executeActivation(state, PaymentPlan(emptyList(), emptyList()))
    }
}

/**
 * The request the open [GameState.pendingActivation] is waiting on (CR 602.2b): the "discard a card"
 * selection first, then the payment plan for the mana component. A pure function of the state (ADR-004).
 */
internal fun pendingActivationRequest(state: GameState): DecisionRequest {
    val pending = state.pendingActivation ?: error("no activation is gathering costs")
    val source =
        activationSource(state, pending.activator, pending.source, pending.sourceObjectId)
            ?: error("CR 602.2: the activation source ${pending.sourceObjectId} is not in its zone ${pending.source}")
    val ability = abilityAt(state, pending.activator, pending.sourceObjectId, pending.source, pending.abilityIndex)
    val id = DecisionRequestId(pending.activator, state.player(pending.activator).decisionsAnswered)
    return if (pending.chosenDiscard == null) {
        DecisionRequest.ChooseAbilityDiscard(
            id = id,
            sourceObjectId = pending.sourceObjectId,
            card = source.card,
            options =
                discardableForAbility(state, pending.activator, source, pending.source)
                    .map { DecisionRequest.ChooseAbilityDiscard.Option(it.id, it.card) },
            count = 1,
        )
    } else {
        val mana = manaComponent(ability) ?: error("CR 602.2g: a payment request requires a mana cost component")
        DecisionRequest.ChoosePaymentPlan(
            id = id,
            cardObjectId = pending.sourceObjectId,
            card = source.card,
            options = enumeratePaymentPlans(state, pending.activator, mana.cost),
        )
    }
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
