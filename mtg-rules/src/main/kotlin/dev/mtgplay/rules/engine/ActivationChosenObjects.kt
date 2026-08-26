package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingActivation
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import kotlinx.collections.immutable.toPersistentList

/*
 * The **chosen-object** cost components of an activated ability (CR 602.1) — the costs whose object the
 * activator picks rather than the card naming it: Krark-Clan Shaman's "Sacrifice an artifact", Quirion
 * Ranger's "Return a Forest you control", and Pinnacle Kill-Ship's "Tap another creature you control"
 * (`W10-C`, which added the third and gave the family a file).
 *
 * All three are one shape and are kept together for that reason: the engine enumerates the candidates,
 * the activator picks by index (ADR-005), the choice is recorded on the open [PendingActivation] while
 * *nothing about the game has changed*, and the cost is paid when the activation executes (CR 602.2b).
 * Their one real interaction with the rest of the pipeline is the same for all three — a chosen
 * permanent is withheld from the mana payment enumerated after it, on the terms [ChosenCostObjects]
 * sets out.
 *
 * Split out of ActivationGathering.kt when the third arrived and pushed that file past detekt's function
 * budget — split rather than suppressed, along the seam CR 602.1 already draws. That file still owns the
 * *order* of the gathering (X, targets, discard, chosen objects, payment) and this owns what each
 * chosen-object stage asks and records. The tap cost's two halves — which permanents may pay it and
 * where the answer is written — are both here, which is what keeps them from disagreeing; the sacrifice
 * and return candidate sets live in SacrificeCosts.kt beside the sacrifice matcher they share.
 *
 * **The tap cost's decision is shared with the cast side.** "Which of your untapped permanents do you tap
 * to pay this cost" is one question whoever is asking it, so both payers use
 * [DecisionRequest.ChooseTapsForCost] and the open pending record decides which one receives the answer
 * (`CastTapCost.kt`'s `applyChosenTapCost`). A second member would have cost a dozen exhaustive dispatch
 * sites to say nothing a seat could act on differently.
 */

/** The chosen-tap component of [ability], or `null` if it has none (CR 602.1). */
internal fun tapComponent(ability: ActivatedAbility): AbilityCost.TapPermanentYouControl? =
    ability.cost.filterIsInstance<AbilityCost.TapPermanentYouControl>().singleOrNull()

/**
 * The permanents [seat] may choose to pay [ability]'s [AbilityCost.TapPermanentYouControl] component:
 * the **untapped** ones matching its filter, less the ability's own source when the printed text says
 * "another" (CR 109.5), that leave the ability's mana component payable once they are reserved
 * (CR 602.1, CR 701.20a).
 *
 * The sibling of [abilityReturnCandidates] and its shape exactly, including the *unconditional*
 * reservation: a permanent tapped to pay this cost cannot also have been tapped for mana. CR 601.2g
 * activates mana abilities before CR 601.2h pays the costs, and a permanent that has already been tapped
 * for mana is not untapped any more, so a plan that spends it twice is not a legal sequencing of one
 * payment — it is a payment the cost then cannot make. `manaSourcesReservedBy` enforces that; this
 * function only asks what remains payable given it.
 *
 * **Untapped is checked here and nowhere else**, because it is intrinsic to the cost rather than a field
 * on the filter (see [AbilityCost.TapPermanentYouControl]). **Summoning sickness is deliberately not
 * checked**: CR 302.6 restricts the `{T}` symbol in an ability *of that permanent*, and the creature
 * tapped here is the source of nothing — so a creature that arrived this turn may pay it, which is the
 * ruling and a real line of play that a gate here would delete (ADR-005).
 *
 * Its two callers must agree exactly, for [abilitySacrificeCandidates]' reason: [abilityCostPayable]
 * asks whether it is non-empty (enumeration, ADR-005) and [pendingActivationRequest] offers it as the
 * selection's options. An ability enumerated against one candidate set and gathered against another
 * would dead-end mid-activation.
 */
internal fun abilityTapCandidates(
    state: GameState,
    seat: PlayerId,
    source: GameObject,
    ability: ActivatedAbility,
): List<GameObject> {
    val component = tapComponent(ability) ?: return emptyList()
    val matching =
        matchingPermanents(state, component.filter, seat)
            .filter { !it.tapped && !(component.another && it.id == source.id) }
    val mana = manaComponent(ability)
    return matching.filter { candidate ->
        mana == null ||
            enumeratePaymentPlans(
                state,
                seat,
                mana.cost,
                manaSourcesReservedBy(state, source, ability, ChosenCostObjects(tapped = listOf(candidate.id))),
            ).isNotEmpty()
    }
}

/**
 * The **chosen-object** cost selection an activation is waiting on (CR 602.1): the sacrifice, the
 * return, or the tap, in that order. Split out of [pendingActivationRequest] when `W10-C` added the
 * third and pushed that dispatch past detekt's length budget — split rather than suppressed, along the
 * seam CR 602.1 already draws between "which object pays this cost" and every other stage.
 *
 * Each offers exactly the candidates the legality check counted, and that agreement is the whole point:
 * an option that left the sibling mana component unpayable would dead-end the activation (ADR-005).
 *
 * Reached only when one of the three is unsettled, so the trailing `error` is unreachable by
 * construction rather than a fallback — [pendingActivationRequest] has just tested the same three.
 */
internal fun chosenObjectRequest(
    state: GameState,
    pending: PendingActivation,
    source: GameObject,
    ability: ActivatedAbility,
    id: DecisionRequestId,
): DecisionRequest =
    when {
        // CR 602.1 with CR 701.17: Krark-Clan Shaman's "Sacrifice an artifact".
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
        // CR 602.1 with CR 701.4a: Quirion Ranger's "Return a Forest you control".
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
        // CR 602.1 with CR 701.20a: Station's "Tap another creature you control". The request is the
        // same member the cast-side tap cost uses — one question, two payers (`AbilityTapCost.kt`).
        pending.chosenTap == null ->
            DecisionRequest.ChooseTapsForCost(
                id = id,
                cardObjectId = pending.sourceObjectId,
                card = source.card,
                options =
                    abilityTapCandidates(state, pending.activator, source, ability)
                        .map { DecisionRequest.ChooseTapsForCost.Option(it.id, it.card) },
                count = 1,
            )
        else -> error("CR 602.1: no chosen-object cost selection is outstanding on this activation")
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

/**
 * Records the permanent chosen to pay an [AbilityCost.TapPermanentYouControl] component on the open
 * activation (CR 602.1) and continues gathering. It is tapped only when the activation executes
 * (CR 602.2b), atomically with everything else — so it is still untapped while the payment plan
 * enumerated next reserves it, and a creature that entered this turn is answerable here (CR 302.6
 * restricts abilities *of* a permanent, and this is a cost of somebody else's).
 */
internal fun applyChosenAbilityTap(
    state: GameState,
    tapObjectIds: List<ObjectId>,
): AdvanceResult {
    val pending = state.pendingActivation ?: error("no activation is gathering costs")
    require(pending.chosenTap == null) { "CR 602.2b: this activation's tap cost is already chosen" }
    return advanceActivationGathering(
        state.copy(pendingActivation = pending.copy(chosenTap = tapObjectIds.toPersistentList())),
    )
}
