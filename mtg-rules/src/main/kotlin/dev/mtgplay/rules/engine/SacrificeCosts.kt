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
 * Whether the battlefield object [obj] matches [filter] (CR 601.2h, CR 300.1, CR 205.3): it has at
 * least one of the filter's card types (or the filter names none) **and** the filter's subtype (or it
 * names none). An object with no definition in the registry is inert and matches nothing.
 *
 * **The single matcher for every sacrifice cost**, since `W8-D` folded the permission-side
 * [dev.mtgplay.core.definition.SacrificeRequirement]'s subtype into [SacrificeFilter]: a cast's
 * additional cost, an activated ability's cost, and a casting permission's cost all ask this one
 * question, so Fireblast's Mountains and Dread Return's creatures cannot be answered by two predicates
 * that drift.
 *
 * **Layered characteristics, not printed ones**, since `FW-TYPECHANGE` populated CR 613 layer 4 and
 * gave [LayeredCharacteristics] a type line to carry. A permanent that *became* an artifact is a legal
 * sacrifice for a cost that names artifacts, and reading printed types would have made that cost
 * unpayable in a position where the rules say it is payable — an ADR-005 defect in the direction that
 * silently removes a line of play, since an unpayable cost means the ability is never enumerated at
 * all. The subtype read goes through [hasSubtype], the one battlefield seam, so a changeling is
 * correctly **not** offered for a *land* subtype — CR 702.73a grants creature types, and Mountain is a
 * land type.
 *
 * **Battlefield only**, which every caller already guarantees: a sacrifice cost sacrifices a permanent
 * (CR 701.17a), and both [sacrificeableMatching] and the permission-side gathering scan the battlefield.
 * The layered read fails loudly on anything else rather than answering from a zone CR 613 does not
 * reach.
 */
internal fun matchesSacrificeFilter(
    state: GameState,
    obj: GameObject,
    filter: SacrificeFilter,
): Boolean {
    if (obj.card !in state.definitions) return false
    val cardTypes = effectiveCardTypes(state, obj.id)
    val typeMatches = filter.anyOfCardTypes.isEmpty() || filter.anyOfCardTypes.any { it in cardTypes }
    val subtypeMatches = filter.subtype?.let { hasSubtype(state, obj.id, it) } ?: true
    return typeMatches && subtypeMatches
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

/** The chosen-return component of [ability], or `null` if it has none (CR 602.1). */
internal fun returnComponent(ability: ActivatedAbility): AbilityCost.ReturnPermanentYouControl? =
    ability.cost.filterIsInstance<AbilityCost.ReturnPermanentYouControl>().singleOrNull()

/**
 * The permanents [seat] may choose to pay [ability]'s [AbilityCost.ReturnPermanentYouControl] component
 * — the ones matching its filter that leave the ability's mana component payable once they are
 * reserved (CR 602.1, CR 701.4a).
 *
 * The sibling of [abilitySacrificeCandidates] and deliberately its shape, with **one difference that
 * matters**: a returned permanent is reserved from the payment plan *unconditionally*, where a
 * sacrificed one is reserved only when it produces mana **by** being sacrificed. The asymmetry is
 * CR 601.2g/h's ordering read through the two zone changes. Tapping a land for mana and then
 * sacrificing it is legal — CR 701.17 does not care that the permanent is tapped — but a permanent
 * returned to its owner's hand becomes a **new object** in a zone with no tapped status at all
 * (CR 400.7, CR 110.5), so a plan that taps a Forest for mana and then returns it would be paying with
 * an object that no longer exists to be returned. `manaSourcesReservedBy` is where that is enforced;
 * this function only has to ask what remains payable given it.
 *
 * Its two callers must agree exactly, for [abilitySacrificeCandidates]' reason: [abilityCostPayable]
 * asks whether it is non-empty (enumeration, ADR-005) and [pendingActivationRequest] offers it as the
 * selection's options. An ability enumerated against one candidate set and gathered against another
 * would dead-end mid-activation.
 *
 * Quirion Ranger's cost is a bare return with no mana component, so every matching Forest is offered
 * and the plan filter below is vacuous — which is the case this function is written to keep honest
 * rather than the case that exercises it.
 */
internal fun abilityReturnCandidates(
    state: GameState,
    seat: PlayerId,
    source: GameObject,
    ability: ActivatedAbility,
): List<GameObject> {
    val filter = returnComponent(ability)?.filter ?: return emptyList()
    val matching = matchingPermanents(state, filter, seat)
    val mana = manaComponent(ability)
    return matching.filter { candidate ->
        mana == null ||
            enumeratePaymentPlans(
                state,
                seat,
                mana.cost,
                manaSourcesReservedBy(state, source, ability, ChosenCostObjects(returned = listOf(candidate.id))),
            ).isNotEmpty()
    }
}

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
                manaSourcesReservedBy(state, source, ability, ChosenCostObjects(sacrificed = listOf(candidate.id))),
            ).isNotEmpty()
    }
}
