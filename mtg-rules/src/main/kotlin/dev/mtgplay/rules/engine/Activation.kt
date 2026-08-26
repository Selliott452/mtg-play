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
 * [AbilityZoneScope.Battlefield] abilities, their hand for [AbilityZoneScope.Hand] abilities, and their
 * graveyard for [AbilityZoneScope.Graveyard] abilities (CR 113.6b — Bramble Wurm's exile-from-graveyard
 * lifegain); an ability is offered only when its whole composite cost is payable ([abilityCostPayable])
 * **and** every target it requires has a legal choice (CR 601.2c via CR 602.2b — an ability that cannot
 * be fully targeted cannot be activated). Battlefield order, then hand order, then graveyard order fixes
 * the option order (ADR-006).
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
        // CR 113.6b: only an ability that says it functions from a graveyard does, which is exactly
        // what declaring AbilityZoneScope.Graveyard says; every other card here contributes nothing.
        state
            .player(seat)
            .graveyard
            .forEach { source -> addAbilities(state, seat, source, AbilityZoneScope.Graveyard) }
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
                // CR 602.5b: "Activate only once each turn" — an ability this object has already
                // activated this turn is not activatable, so it is not enumerated (ADR-005). The record
                // is per object, not per controller, which is CR 602.5b's own wording.
                !(ability.oncePerTurn && index in source.activatedAbilitiesActivatedThisTurn) &&
                // CR 602.5d: "Activate only as a sorcery" restricts the ability to a sorcery's timing
                // window. Enumerating it outside that window would be an enumerated-but-illegal action
                // (ADR-005), which is the whole reason `ActivatedAbility.timing` exists.
                timingPermitsWindow(state, seat, ability.timing) &&
                // CR 601.2c via CR 602.2b: an ability with no legal target cannot be activated. An
                // ability's source is a permanent or a card in hand, never a spell on the stack, so
                // there is nothing for it to exclude from its own enumeration — but CR 702.16b needs
                // that source's characteristics, and this is where a missed protection check would
                // become a phantom *action* rather than merely a phantom target
                // (docs/design/protection.md §6, row 3).
                targetsAvailable(state, ability.targetSpec, seat, Chooser.Ability(source.card)) &&
                abilityCostPayable(state, seat, source, scope, ability)
        if (offerable) {
            add(PriorityOption.ActivateAbility(source.id, source.card, index, scope))
        }
    }
}

/**
 * Whether every component of [ability]'s cost is payable by [seat] for [source] right now (CR 602.2).
 *
 * The mana and chosen-object components are checked **jointly** rather than one at a time, because
 * they constrain each other: which permanent is sacrificed or returned decides what may be tapped for
 * the mana (docs/design/mana-payment.md §2.2). [abilitySacrificeCandidates] and
 * [abilityReturnCandidates] are that joint answer, so an ability carrying one of them defers its mana
 * question to that branch and the mana branch is vacuously true.
 *
 * No card in the gauntlet carries **both** a sacrifice and a return component; if one ever does, the
 * two joint answers would each be reserving without seeing the other's choice, so the mana branch's
 * short-circuit below fails loudly rather than silently over-offering.
 */
internal fun abilityCostPayable(
    state: GameState,
    seat: PlayerId,
    source: GameObject,
    scope: AbilityZoneScope,
    ability: ActivatedAbility,
): Boolean {
    require(sacrificeComponent(ability) == null || returnComponent(ability) == null) {
        "CR 602.1: ${source.card.name} costs both a chosen sacrifice and a chosen return; the two " +
            "joint payability answers each reserve without seeing the other's choice, so a cost " +
            "carrying both needs one combined enumeration (docs/design/mana-payment.md §2.2)"
    }
    // CR 113.6: the only caller reaches this having already matched the ability's declared zone against
    // the zone it found the source in, which is what lets [componentPayable] read one of them.
    require(ability.zoneScope == scope) {
        "CR 113.6: ${source.card.name}'s ability functions from ${ability.zoneScope}, but its " +
            "payability was asked about $scope"
    }
    return ability.cost.all { component -> componentPayable(state, seat, source, ability, component) }
}

/**
 * Whether one [component] of [ability]'s cost is payable (CR 602.2), the body of
 * [abilityCostPayable]'s fold. Split out to keep that function inside detekt's complexity budget; the
 * `when` is exhaustive, so a new [AbilityCost] member breaks compilation here.
 *
 * Reads the ability's own [ActivatedAbility.zoneScope] rather than taking the caller's zone as a
 * parameter — [abilityCostPayable] has just required the two to agree, so there is one source of truth
 * for the zone and one fewer argument to keep in step.
 */
private fun componentPayable(
    state: GameState,
    seat: PlayerId,
    source: GameObject,
    ability: ActivatedAbility,
    component: AbilityCost,
): Boolean =
    when (component) {
        // The source is excluded from funding its own cost when a sibling component has claimed
        // it (CR 602.1, triage trap T17) — otherwise legality would say yes to a plan that taps
        // the very permanent the `{T}` component then needs untapped.
        is AbilityCost.Mana ->
            sacrificeComponent(ability) != null ||
                returnComponent(ability) != null ||
                enumeratePaymentPlans(
                    state,
                    seat,
                    component.cost,
                    manaSourcesReservedBy(state, source, ability),
                ).isNotEmpty()
        AbilityCost.TapSelf ->
            ability.zoneScope == AbilityZoneScope.Battlefield &&
                !source.tapped &&
                !(isCreature(state, source) && source.summoningSick && !hasHaste(state, source.id))
        // CR 701.17 / CR 701.8 / CR 701.3a: every cost that consumes its own source asks one question
        // — is the source in the zone this cost can take it from? Unlike `{T}`, neither tapped status
        // nor summoning sickness bears on any of them, and where the object *goes* is a payment
        // concern rather than a payability one. Two packets wrote these arms independently, one
        // through the helper and one inline; the helper form is kept because it is the single place
        // the cost-to-zone pairing is stated.
        AbilityCost.SacrificeSelf,
        AbilityCost.DiscardSelf,
        AbilityCost.ExileSelfFromGraveyard,
        AbilityCost.ExileSelf,
        -> selfCostMatchesZone(ability.zoneScope, component)
        AbilityCost.DiscardACard -> discardableForAbility(state, seat, source, ability.zoneScope).isNotEmpty()
        // CR 118.4: energy is paid from a running per-player total, so payability is one comparison and
        // there is nothing to choose. The ability is simply not enumerated below the threshold (ADR-005).
        is AbilityCost.Energy -> state.player(seat).energyCounters >= component.amount
        // CR 602.1 with CR 701.17: at least one permanent both matches the filter and leaves the
        // sibling mana component payable once reserving it is accounted for.
        is AbilityCost.Sacrifice -> abilitySacrificeCandidates(state, seat, source, ability).isNotEmpty()
        // CR 602.1 with CR 701.4a: the same joint answer, over the return cost's candidates.
        is AbilityCost.ReturnPermanentYouControl ->
            abilityReturnCandidates(state, seat, source, ability).isNotEmpty()
    }

/**
 * Whether a cost component that consumes **its own source** is payable given the ability's zone
 * (CR 602.1, CR 113.6) — the three costs that name no chosen object and so ask nothing but "is my source
 * where this cost can take it from?".
 *
 * Each pairs with exactly one zone by construction: a sacrifice takes a battlefield permanent
 * (CR 701.17), a self-discard takes a hand card (CR 701.8), a graveyard self-exile takes a graveyard
 * card and a battlefield self-exile takes a permanent (CR 701.3a, the two halves of Relic of
 * Progenitus and Bramble Wurm). The caller has already matched the source's zone against the ability's declared
 * scope, so agreeing here is the whole test — nothing else can make one of them unpayable, since none of
 * them can be blocked by another object and none of them can already have been spent.
 */
private fun selfCostMatchesZone(
    scope: AbilityZoneScope,
    component: AbilityCost,
): Boolean =
    when (component) {
        AbilityCost.SacrificeSelf -> scope == AbilityZoneScope.Battlefield
        AbilityCost.DiscardSelf -> scope == AbilityZoneScope.Hand
        AbilityCost.ExileSelfFromGraveyard -> scope == AbilityZoneScope.Graveyard
        // CR 701.3a again, from the other side: Relic of Progenitus exiles itself *from the
        // battlefield*. Same keyword action, different zone, and so a different cost — which is why
        // the two are separate members rather than one parameterised by scope.
        AbilityCost.ExileSelf -> scope == AbilityZoneScope.Battlefield
        is AbilityCost.Mana,
        AbilityCost.TapSelf,
        AbilityCost.DiscardACard,
        is AbilityCost.Energy,
        is AbilityCost.Sacrifice,
        is AbilityCost.ReturnPermanentYouControl,
        -> error("CR 602.1: $component does not consume its own source")
    }

/** The hand cards [seat] may discard to a "discard a card" cost — every hand card but a hand source itself. */
internal fun discardableForAbility(
    state: GameState,
    seat: PlayerId,
    source: GameObject,
    scope: AbilityZoneScope,
): List<GameObject> = state.player(seat).hand.filter { !(scope == AbilityZoneScope.Hand && it.id == source.id) }
