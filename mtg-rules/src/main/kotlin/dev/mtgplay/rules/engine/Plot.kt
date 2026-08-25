package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingPlot
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.decision.PriorityOption

/*
 * Plot (CR 702.140), the two halves of the mechanic:
 *  - the plot special action (CR 116.2g): pay a card's plot cost and exile it from hand face-up with a
 *    plotted-turn marker, at sorcery speed, keeping priority — modeled here as a payment gathering
 *    reusing the mana-payment machinery, like a cast but with no stack and no spell;
 *  - the free cast from exile: enumerated by the ordinary permission scan
 *    ([permissionCastOptions]) via [CastingPermission.Plot], gated here to a plotted card on a later
 *    turn ([plotFreeCastLegal]).
 */

/** The single plot casting permission of [card]'s definition (CR 702.140), or `null` if it has none. */
internal fun plotPermissionOf(
    state: GameState,
    card: dev.mtgplay.core.identity.CardRef,
): CastingPermission.Plot? =
    (state.definitions[card] as? SpellDefinition)
        ?.castingPermissions
        ?.filterIsInstance<CastingPermission.Plot>()
        ?.singleOrNull()

/**
 * The plot special-action options for [seat] (CR 702.140, CR 116.2g): one [PriorityOption.PlotCard] per
 * hand card whose definition carries a [CastingPermission.Plot], legal to plot right now (ADR-005) —
 * sorcery timing ([plotTimingLegal]) and an affordable plot cost. Hand order fixes the option order.
 */
internal fun plotOptions(
    state: GameState,
    seat: PlayerId,
): List<PriorityOption.PlotCard> {
    if (!plotTimingLegal(state, seat)) return emptyList()
    return state.player(seat).hand.mapNotNull { obj ->
        val permission = plotPermissionOf(state, obj.card) ?: return@mapNotNull null
        if (enumeratePaymentPlans(state, seat, permission.plotCost).isEmpty()) {
            null
        } else {
            PriorityOption.PlotCard(obj.id, obj.card)
        }
    }
}

/**
 * Whether [seat] may take the plot special action right now (CR 116.2g, CR 702.140a): they are the
 * active player, in a main phase of their own turn, with the stack empty — the same sorcery-speed
 * window a land play uses (CR 116.2a). Priority is the caller's concern.
 */
internal fun plotTimingLegal(
    state: GameState,
    seat: PlayerId,
): Boolean =
    seat == state.turn.activePlayer &&
        (
            state.turn.phase == dev.mtgplay.core.state.TurnPhase.PRECOMBAT_MAIN ||
                state.turn.phase == dev.mtgplay.core.state.TurnPhase.POSTCOMBAT_MAIN
        ) &&
        state.sharedZones.stack.isEmpty()

/**
 * Whether the free cast (CR 702.140) of the plotted exile object [sourceObject] is legal for [seat]: it
 * was plotted ([GameObject.plottedTurn] set) and **not this turn** — the plotted turn is strictly
 * before the current turn. The mana cost ({0}) and target/timing gates are checked by the ordinary
 * permission-cast legality alongside this.
 */
internal fun plotFreeCastLegal(
    state: GameState,
    sourceObject: GameObject,
): Boolean {
    val plottedTurn = sourceObject.plottedTurn ?: return false
    return plottedTurn < state.turn.number
}

/**
 * Begins the plot special action for [seat]'s hand card [cardObjectId] (CR 702.140): opens a
 * [PendingPlot] and suspends for the plot cost's payment plan. Legality was checked at enumeration
 * (ADR-005); the card is still in hand and nothing has changed until the payment arrives.
 */
internal fun beginPlot(
    state: GameState,
    seat: PlayerId,
    cardObjectId: ObjectId,
): AdvanceResult {
    val gathering = state.copy(pendingPlot = PendingPlot(seat, cardObjectId))
    return AdvanceResult.NeedsDecision(gathering, pendingPlotRequest(gathering))
}

/**
 * The payment request the open [GameState.pendingPlot] is waiting on (CR 702.140): the plotting player
 * chooses how to pay the card's plot cost. A pure function of the state (ADR-004).
 */
internal fun pendingPlotRequest(state: GameState): DecisionRequest.ChoosePaymentPlan {
    val plot = state.pendingPlot ?: error("no plot action is gathering a payment")
    val card =
        state.player(plot.caster).hand.firstOrNull { it.id == plot.cardObjectId }
            ?: error("CR 702.140: the plotted card ${plot.cardObjectId} is not in ${plot.caster}'s hand")
    val permission =
        plotPermissionOf(state, card.card) ?: error("CR 702.140: ${card.card.name} has no plot permission")
    return DecisionRequest.ChoosePaymentPlan(
        id = DecisionRequestId(plot.caster, state.player(plot.caster).decisionsAnswered),
        cardObjectId = plot.cardObjectId,
        card = card.card,
        // CR 702.140b: the plot cost is paid by a special action, not by casting, so CR 601.2f never
        // runs over it and no cost reduction applies (docs/design/cost-modification.md §12). The
        // determined cost is the printed plot cost.
        cost = permission.plotCost,
        options = enumeratePaymentPlans(state, plot.caster, permission.plotCost),
    )
}

/**
 * Executes the plot special action with the chosen [plan] (CR 702.140): pays the plot cost, then exiles
 * the card from the caster's hand as a **new** object (CR 400.7) marked plotted this turn
 * ([GameObject.plottedTurn]), and returns priority to the caster (CR 116.4 — a special action does not
 * pass priority) in a fresh round. Runs as one pure transition, like the play-land action.
 */
internal fun executePlot(
    state: GameState,
    plan: PaymentPlan,
): AdvanceResult {
    val plot = state.pendingPlot ?: error("no plot action is gathering a payment")
    val cleared = state.copy(pendingPlot = null)
    val handIndex = cleared.player(plot.caster).hand.indexOfFirst { it.id == plot.cardObjectId }
    require(handIndex >= 0) { "CR 702.140: the plotted card ${plot.cardObjectId} is not in ${plot.caster}'s hand" }
    val handCard = cleared.player(plot.caster).hand[handIndex]
    val permission =
        plotPermissionOf(cleared, handCard.card) ?: error("CR 702.140: ${handCard.card.name} has no plot permission")
    val plotCost: ManaCost = permission.plotCost
    val paid = payManaPlan(cleared, plot.caster, plotCost, plan)
    val (exileId, allocated) = paid.allocateObjectId()
    // CR 400.7 / CR 702.140: the exiled object is fresh and carries the plotted-turn marker.
    val plotted =
        GameObject(id = exileId, card = handCard.card, owner = plot.caster, plottedTurn = allocated.turn.number)
    val moved =
        allocated
            .updatePlayer(plot.caster) { it.copy(hand = it.hand.removingAt(handIndex)) }
            .updateExile { it.adding(plotted) }
            .emit(GameEvent.CardPlotted(plot.caster, exileId, handCard.card))
    // CR 116.4: a special action does not pass priority; the caster keeps it, pass-flags reset.
    return priorityTo(clearPriorityRound(moved), plot.caster)
}
