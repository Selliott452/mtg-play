package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.SacrificeFilter
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState

/*
 * Sacrifice **costs** with a chosen object (`FW-ADDSAC`): which permanents satisfy a
 * [SacrificeFilter], and — the part that is not obvious — how a chosen sacrifice interacts with the
 * mana payment enumerated alongside it (docs/design/mana-payment.md §2.2).
 *
 * The two cost shapes this file serves are [dev.mtgplay.core.definition.AdditionalCost.Sacrifice] on
 * the cast side and [AbilityCost.Sacrifice] on the activation side. Both are *costs*: they are paid at
 * CR 601.2h / CR 602.2b inside the single atomic transition that completes the cast or activation, no
 * player receives priority between the choice and the payment, and a cost that cannot be paid means the
 * spell or ability is **not enumerated at all** rather than offered and then abandoned (ADR-005).
 *
 * They live here, together, for the reason [manaSourceUsable] lives in its own file: the matcher and
 * the reservation rule each have several callers that must never disagree.
 */

/**
 * Whether the battlefield object [obj] matches [filter] (CR 601.2h, CR 300.1): it has at least one of
 * the filter's card types. An object with no definition in the registry is inert and matches nothing.
 *
 * **Printed card types, not layered ones.** [LayeredCharacteristics] models power, toughness, keywords,
 * and mana abilities, but not types: no CR 613 layer-4 type-changing effect exists in the pool, so
 * there is nothing for a layered read to differ on. This is the same read
 * [sacrificeableFor] already makes for the permission-side cost's subtype, and it is where a
 * type-changing effect must be wired in when one arrives.
 */
internal fun matchesSacrificeFilter(
    state: GameState,
    obj: GameObject,
    filter: SacrificeFilter,
): Boolean {
    val types = state.definitions[obj.card]?.characteristics?.cardTypes ?: return false
    return filter.anyOfCardTypes.any { it in types }
}

/**
 * The battlefield permanents [seat] controls that match [filter], in battlefield order (CR 601.2h) —
 * the option set of a sacrifice cost's selection, and the membership its payability counts. Control is
 * ownership in the MVP pool, as it is for [sacrificeableFor].
 */
internal fun sacrificeableMatching(
    state: GameState,
    seat: PlayerId,
    filter: SacrificeFilter,
): List<GameObject> =
    state.sharedZones.battlefield.filter { obj ->
        obj.owner == seat && matchesSacrificeFilter(state, obj, filter)
    }

/**
 * The mana sources that must not fund a cost whose sibling sacrifice component has chosen
 * [chosenSacrifice] (docs/design/mana-payment.md §2.2, triage trap T17), and **only** those.
 *
 * A permanent chosen to be sacrificed is reserved exactly when it is a *sacrifice*-cost mana source
 * ([isSacrificeSource]) — an Eldrazi Spawn's "Sacrifice this token: Add {C}" — because producing mana
 * from it consumes it before the cost's own sacrifice can, and the sacrifice would then fail loudly on
 * a permanent that is no longer on the battlefield.
 *
 * **A tapped permanent is not reserved, and that is the point.** Tapping a land for mana and then
 * sacrificing it is legal Magic — CR 601.2g (activate mana abilities) precedes CR 601.2h (pay costs),
 * and CR 701.17 does not care that the permanent is tapped — so those plans stay enumerated. Reserving
 * every chosen permanent would be the easy blunt rule and the wrong one: it trades a crash for a
 * silently missing legal play, which is the worse of the two failures.
 */
internal fun sacrificeSourcesAmong(
    state: GameState,
    chosenSacrifice: List<ObjectId>,
): Set<ObjectId> = chosenSacrifice.filter { isSacrificeSource(state, it) }.toSet()

/** The mana cost component of [ability], or `null` if it has none (CR 602.1). */
internal fun manaComponent(ability: ActivatedAbility): AbilityCost.Mana? =
    ability.cost.filterIsInstance<AbilityCost.Mana>().singleOrNull()

/** The chosen-sacrifice component of [ability], or `null` if it has none (CR 602.1). */
internal fun sacrificeComponent(ability: ActivatedAbility): AbilityCost.Sacrifice? =
    ability.cost.filterIsInstance<AbilityCost.Sacrifice>().singleOrNull()

/**
 * The permanents [seat] may choose to pay [ability]'s [AbilityCost.Sacrifice] component — the ones that
 * match its filter **and** leave the ability's mana component payable once they are reserved.
 *
 * This is the joint check the two halves of the cost need, and it has exactly two callers by design:
 * [abilityCostPayable] asks whether it is non-empty (enumeration, ADR-005) and
 * [dev.mtgplay.rules.engine.pendingActivationRequest] offers it as the selection's options. They must
 * agree exactly — an ability enumerated against one candidate set and gathered against another would
 * dead-end mid-activation, which is the ADR-005 failure this whole path exists to prevent.
 *
 * The filtering is per candidate rather than blanket, which is what keeps it from over-reserving: a
 * candidate that is not a sacrifice-cost mana source reserves nothing and is always offerable if the
 * mana is payable at all, and a candidate that *is* one is dropped only when it is itself the reason no
 * plan exists. Returns every matching permanent for an ability with no mana component, which is
 * Krark-Clan Shaman's case.
 */
internal fun abilitySacrificeCandidates(
    state: GameState,
    seat: PlayerId,
    source: GameObject,
    ability: ActivatedAbility,
): List<GameObject> {
    val filter = sacrificeComponent(ability)?.filter ?: return emptyList()
    val matching = sacrificeableMatching(state, seat, filter)
    val mana = manaComponent(ability)
    return matching.filter { candidate ->
        mana == null ||
            enumeratePaymentPlans(
                state,
                seat,
                mana.cost,
                manaSourcesReservedBy(state, source, ability, listOf(candidate.id)),
            ).isNotEmpty()
    }
}
