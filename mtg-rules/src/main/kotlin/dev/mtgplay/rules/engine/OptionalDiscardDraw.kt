package dev.mtgplay.rules.engine

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingOptionalDiscardDraw
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.effect.drawCards

/*
 * The optional "you may discard a card; if you do, draw N" resolution flow (CR 601.3b) — Melded Moxite's
 * enters-the-battlefield clause, the P5.2 madness pattern generalized. When a triggered ability carrying
 * this clause resolves, the ability leaves the stack and the engine offers its controller a yes/no; on
 * yes it gathers a discard selection, discards the card through the CR 614/616 framework (so a discarded
 * madness card is exiled instead), and draws; on no — or when the controller has no card to discard —
 * nothing more happens.
 */

/**
 * Resolves a triggered ability whose clause is an optional discard-then-draw (CR 601.3b): the ability
 * ceases to exist (CR 113.7a), then, if the controller has at least one card to discard, the engine
 * pauses for the yes/no; otherwise the clause does nothing and a fresh priority round opens.
 */
internal fun resolveOptionalDiscardDrawTrigger(
    state: GameState,
    entry: StackEntry.Ability,
): AdvanceResult {
    check(state.sharedZones.stack.lastOrNull() == entry) { "CR 608.1: only the topmost stack object may resolve" }
    val trigger = entry.trigger
    val clause =
        trigger.ability.optionalDiscardDraw
            ?: error("resolveOptionalDiscardDrawTrigger requires an optional-discard-draw clause")
    val controller = trigger.controller
    val ceased =
        state
            .updateStack { it.removingAt(it.lastIndex) }
            .emit(GameEvent.TriggeredAbilityResolved(controller, trigger.sourceCard))
    // CR 601.3b: with no card to discard, the "may" cannot be taken — the clause does nothing.
    if (ceased.player(controller).hand.isEmpty()) return grantPriorityRound(ceased)
    val paused =
        ceased.copy(
            pendingOptionalDiscardDraw =
                PendingOptionalDiscardDraw(
                    controller,
                    clause.drawCount,
                    awaitingDiscard = false,
                ),
        )
    return AdvanceResult.NeedsDecision(paused, pendingOptionalDiscardYesNoRequest(paused))
}

/** The yes/no the open optional-discard-draw is waiting on (CR 601.3b). A pure function of the state. */
internal fun pendingOptionalDiscardYesNoRequest(state: GameState): DecisionRequest.ChooseYesNo {
    val pending = state.pendingOptionalDiscardDraw ?: error("no optional discard-draw is pending")
    val source = state.player(pending.decider).hand.first()
    return DecisionRequest.ChooseYesNo(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        prompt = "discard a card to draw ${pending.drawCount}",
        cardObjectId = source.id,
        card = source.card,
    )
}

/** The discard selection the accepted optional-discard-draw is waiting on (CR 701.8). Pure per ADR-004. */
internal fun pendingOptionalDiscardSelectionRequest(state: GameState): DecisionRequest.ChooseOptionalDiscard {
    val pending = state.pendingOptionalDiscardDraw ?: error("no optional discard-draw is pending")
    return DecisionRequest.ChooseOptionalDiscard(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        options =
            state
                .player(
                    pending.decider,
                ).hand
                .map { DecisionRequest.ChooseOptionalDiscard.Option(it.id, it.card) },
        count = 1,
    )
}

/**
 * Applies the optional-discard-draw yes/no (CR 601.3b): [accept] `true` pauses for the discard
 * selection; [accept] `false` clears the clause with no discard and no draw, opening a fresh priority
 * round.
 */
internal fun applyOptionalDiscardYesNo(
    state: GameState,
    accept: Boolean,
): AdvanceResult {
    val pending = state.pendingOptionalDiscardDraw ?: error("no optional discard-draw is pending")
    return if (accept) {
        val awaiting = state.copy(pendingOptionalDiscardDraw = pending.copy(awaitingDiscard = true))
        AdvanceResult.NeedsDecision(awaiting, pendingOptionalDiscardSelectionRequest(awaiting))
    } else {
        grantPriorityRound(state.copy(pendingOptionalDiscardDraw = null))
    }
}

/**
 * Applies the discard selection of an accepted optional-discard-draw (CR 601.3b, CR 701.8): discards
 * [discardObjectId] through the CR 614/616 framework (so madness intercepts it) and draws the clause's
 * cards, then opens a fresh priority round.
 */
internal fun applyOptionalDiscardChoice(
    state: GameState,
    discardObjectId: ObjectId,
): AdvanceResult {
    val pending = state.pendingOptionalDiscardDraw ?: error("no optional discard-draw is pending")
    val cleared = state.copy(pendingOptionalDiscardDraw = null)
    val discarded = discardApplyingReplacements(cleared, pending.decider, discardObjectId)
    // CR 601.3b: "if you do, draw" — the draw follows the completed discard.
    return grantPriorityRound(drawCards(discarded, pending.decider, pending.drawCount))
}
