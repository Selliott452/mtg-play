package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.AdditionalCost
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingCast
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId

/*
 * The pending decision a cast in progress is waiting on (CR 601.2), split from PendingDecision.kt so
 * each file stays within its function budget. The gathering order is fixed: modes (601.2b), targets
 * (601.2c), then the additional-exile / sacrifice / additional-discard cost selections (601.2b/h), then
 * the payment plan (601.2g) — always surfaced, even with a single plan, so replay logs stay canonical
 * (P2.1).
 *
 * Modes come first because CR 601.2b puts them first, and `FW-MODAL` is the packet that made the
 * ordering observable: until then no card had modes, so the stage was a documented no-op.
 */

/**
 * The request the open [cast] is waiting on (CR 601.2). A pure function of the state (ADR-004): each
 * gathered-so-far choice on [cast] settles one stage, and this re-derives the next unanswered one.
 */
internal fun pendingCastRequest(
    state: GameState,
    cast: PendingCast,
): DecisionRequest {
    val card =
        objectInZone(state, cast.caster, cast.source, cast.cardObjectId)
            ?: error("CR 601.2: pending cast's card ${cast.cardObjectId} is not in ${cast.caster}'s ${cast.source}")
    val definition = spellDefinitionOf(state, card.card)
    val id = DecisionRequestId(cast.caster, state.player(cast.caster).decisionsAnswered)
    return when {
        // CR 601.2b: modes first, and the precedence is load-bearing rather than ceremonial — a modal
        // card's modes may target different *kinds* of object (Blue Elemental Blast counters a spell or
        // destroys a permanent), so the targets branch below has no enumeration to run until the mode is
        // settled. Only choosable modes are offered (ADR-005).
        cast.chosenModes == null ->
            DecisionRequest.ChooseModes(
                id = id,
                cardObjectId = cast.cardObjectId,
                card = card.card,
                options =
                    castableModes(state, definition, cast.caster, Chooser.Spell(cast.cardObjectId))
                        .map { DecisionRequest.ChooseModes.Option(it, definition.modes[it].text) },
            )
        // CR 601.2c: then targets, enumerated against the spec the settled mode put in force. The modes
        // are non-null in this branch, but they are a cross-module property so the compiler will not
        // smart-cast them; `orEmpty()` is the same value, and a non-modal card's is empty anyway.
        cast.chosenTargets == null ->
            targetRequest(
                id = id,
                cardObjectId = cast.cardObjectId,
                card = card.card,
                spec = effectiveTargetSpec(definition, cast.chosenModes.orEmpty()),
                // CR 601.2c: the card is still in its source zone while gathering, so its id excludes
                // nothing from the stack — and naming it here is what makes this enumeration equal the
                // one `establishTargets` recomputes once the card is on the stack under a fresh id.
                options =
                    legalTargets(
                        state,
                        effectiveTargetSpec(definition, cast.chosenModes.orEmpty()),
                        cast.caster,
                        Chooser.Spell(cast.cardObjectId),
                    ),
            )
        // CR 601.2b: then any additional "exile N other cards" cost selection (escape).
        cast.additionalExileCost == null -> chooseCardsToExileRequest(state, cast, card.card, id)
        // CR 601.2h: then any non-mana sacrifice cost selection (Fireblast, Lava Dart).
        cast.sacrificeCost == null -> chooseSacrificesRequest(state, cast, card.card, id)
        // CR 601.2b: then any additional discard cost selection (Grab the Prize).
        cast.additionalDiscard == null -> chooseDiscardForCostRequest(state, cast, definition, card.card, id)
        // CR 601.2b: then any intrinsic sacrifice additional cost selection (Eviscerator's Insight).
        cast.additionalSacrifice == null -> chooseSacrificeForCostRequest(state, cast, definition, card.card, id)
        // CR 601.2g: finally the payment plan for the (possibly alternative) mana cost.
        else -> {
            // CR 601.2f: the same shared function legality and the pipeline use, with the card still
            // in its source zone and therefore excluded from its own zone counts (CR 601.2a) — which
            // is what makes this cost equal the one `determineTotalCost` recomputes at execution.
            val cost =
                totalCost(state, cast.caster, definition, cast.castingPermission, cast.cardObjectId)
            DecisionRequest.ChoosePaymentPlan(
                id = id,
                cardObjectId = cast.cardObjectId,
                card = card.card,
                cost = cost,
                // A permanent already chosen for the sacrifice additional cost is excluded from
                // funding the mana **only** when it produces mana by being sacrificed — spending it
                // would consume it before the cost's own sacrifice. Tapping a chosen land for mana
                // and then sacrificing it is legal and stays enumerated
                // (docs/design/mana-payment.md §2.2).
                options =
                    enumeratePaymentPlans(
                        state,
                        cast.caster,
                        cost,
                        sacrificeSourcesAmong(state, cast.additionalSacrifice.orEmpty()),
                    ),
            )
        }
    }
}

// CR 601.2b/702.139a: every card in the source zone other than the one being cast is exilable (escape).
private fun chooseCardsToExileRequest(
    state: GameState,
    cast: PendingCast,
    card: CardRef,
    id: DecisionRequestId,
): DecisionRequest.ChooseCardsToExile {
    val permission =
        cast.castingPermission ?: error("CR 601.2b: an additional exile cost requires a casting permission")
    return DecisionRequest.ChooseCardsToExile(
        id = id,
        cardObjectId = cast.cardObjectId,
        card = card,
        options =
            objectsInZone(state, cast.caster, cast.source)
                .filter { it.id != cast.cardObjectId }
                .map { DecisionRequest.ChooseCardsToExile.Option(it.id, it.card) },
        count = permission.additionalExileCount,
    )
}

// CR 601.2h: every matching permanent the caster controls is a sacrifice option (Fireblast, Lava Dart).
private fun chooseSacrificesRequest(
    state: GameState,
    cast: PendingCast,
    card: CardRef,
    id: DecisionRequestId,
): DecisionRequest.ChooseSacrifices {
    val requirement =
        cast.castingPermission?.sacrifice ?: error("CR 601.2h: a sacrifice cost requires a casting permission")
    return DecisionRequest.ChooseSacrifices(
        id = id,
        cardObjectId = cast.cardObjectId,
        card = card,
        options =
            sacrificeableFor(state, cast.caster, requirement)
                .map { DecisionRequest.ChooseSacrifices.Option(it.id, it.card) },
        count = requirement.count,
    )
}

// CR 601.2b: every matching permanent the caster controls is an additional-sacrifice-cost option
// (Eviscerator's Insight's "an artifact or creature", Raze's "a land"). The card being cast is in the
// hand or the graveyard, never on the battlefield, so it excludes nothing from its own option list.
private fun chooseSacrificeForCostRequest(
    state: GameState,
    cast: PendingCast,
    definition: dev.mtgplay.core.definition.SpellDefinition,
    card: CardRef,
    id: DecisionRequestId,
): DecisionRequest.ChooseSacrificesForCost {
    val additional =
        definition.additionalCost as? AdditionalCost.Sacrifice
            ?: error("CR 601.2b: an additional sacrifice cost requires a sacrifice additional cost")
    return DecisionRequest.ChooseSacrificesForCost(
        id = id,
        cardObjectId = cast.cardObjectId,
        card = card,
        options =
            sacrificeableMatching(state, cast.caster, additional.filter)
                .map { DecisionRequest.ChooseSacrificesForCost.Option(it.id, it.card) },
        count = additional.count,
    )
}

// CR 601.2b: every card in the caster's hand except the one being cast is a discard-cost option (Grab the Prize).
private fun chooseDiscardForCostRequest(
    state: GameState,
    cast: PendingCast,
    definition: dev.mtgplay.core.definition.SpellDefinition,
    card: CardRef,
    id: DecisionRequestId,
): DecisionRequest.ChooseCardsToDiscardForCost {
    val additional =
        definition.additionalCost as? AdditionalCost.DiscardCards
            ?: error("CR 601.2b: an additional discard cost requires a discard additional cost")
    return DecisionRequest.ChooseCardsToDiscardForCost(
        id = id,
        cardObjectId = cast.cardObjectId,
        card = card,
        options =
            state
                .player(cast.caster)
                .hand
                .filter { it.id != cast.cardObjectId }
                .map { DecisionRequest.ChooseCardsToDiscardForCost.Option(it.id, it.card) },
        count = additional.count,
    )
}
