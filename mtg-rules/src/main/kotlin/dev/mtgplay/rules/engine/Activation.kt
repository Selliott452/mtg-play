package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.PriorityOption

/*
 * The **enumeration** half of general activated abilities (CR 602): which non-mana activated abilities
 * of permanents (and, hand-scoped, of cards in hand) a seat may activate right now. Gathering the
 * activation's choices lives in ActivationGathering.kt and executing it in ActivationExecution.kt, so
 * each file stays within its function budget. Mana abilities (CR 605) are a separate, stackless path.
 *
 * **An activated ability with no legal target cannot be activated at all** (CR 601.2c via CR 602.2b), so
 * it is absent from enumeration rather than offered and then abandoned (ADR-005). That is the exact
 * opposite of a triggered ability, which is put on the stack with no targets and then does nothing
 * (CR 603.3d, CR 608.2b) — docs/design/targeted-abilities.md §2.1.
 */

/**
 * The activate-ability options for [seat] (CR 602.1, CR 117.1c): one [PriorityOption.ActivateAbility] per
 * (source, ability) the seat may activate right now (ADR-005). Scans [seat]'s battlefield permanents for
 * [AbilityZoneScope.Battlefield] abilities and their hand for [AbilityZoneScope.Hand] abilities; an
 * ability is offered only when its whole composite cost is payable ([abilityCostPayable]) **and** every
 * target it requires has a legal choice (CR 601.2c via CR 602.2b — an ability that cannot be fully
 * targeted cannot be activated). Battlefield order then hand order fixes the option order (ADR-006).
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
        val offerable =
            ability.zoneScope == scope &&
                // CR 601.2c via CR 602.2b: an ability with no legal target cannot be activated.
                targetsAvailable(state, ability.targetSpec, seat) &&
                abilityCostPayable(state, seat, source, scope, ability)
        if (offerable) {
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
            // The source is excluded from funding its own cost when a sibling component has claimed
            // it (CR 602.1, triage trap T17) — otherwise legality would say yes to a plan that taps
            // the very permanent the `{T}` component then needs untapped.
            is AbilityCost.Mana ->
                enumeratePaymentPlans(
                    state,
                    seat,
                    component.cost,
                    manaSourcesReservedBy(state, source, ability),
                ).isNotEmpty()
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
internal fun discardableForAbility(
    state: GameState,
    seat: PlayerId,
    source: GameObject,
    scope: AbilityZoneScope,
): List<GameObject> = state.player(seat).hand.filter { !(scope == AbilityZoneScope.Hand && it.id == source.id) }
