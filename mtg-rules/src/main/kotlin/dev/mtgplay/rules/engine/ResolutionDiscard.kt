package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.DrawThenDiscard
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingResolutionDiscard
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.resolutionController
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.effect.drawCards

/*
 * The mandatory "draw N cards, then discard M cards" spell-resolution flow (CR 601.2c) — Faithless Looting.
 * The draws happen first, then the engine pauses for a mandatory selection of exactly M hand cards (clamped
 * to the hand size), each discarded through the CR 614/616 framework — so a discarded madness card (Fiery
 * Temper) is exiled instead and its reflexive cast fires, the Madness deck's flagship loot-into-madness line.
 * The resolving object stays on top of the stack during the pause (like the library-reveal flow), so the
 * pending discard is a pure derivation of the state (ADR-004). The clause is carried by
 * [dev.mtgplay.core.definition.ResolutionClauses], so a resolving ability loots through this flow too
 * (`FW-CLAUSEHOOK`).
 */

/**
 * Runs a spell's "draw N, then discard M" resolution [clause] (CR 601.2c): draws the cards, then — if the
 * hand holds any after the draw — pauses for the mandatory discard of exactly `min(M, hand size)`; with an
 * empty hand there is nothing to discard, so the spell simply leaves the stack. The resolving spell [entry]
 * stays on top of the stack during the pause.
 */
internal fun orchestrateDrawThenDiscard(
    state: GameState,
    entry: StackEntry,
    clause: DrawThenDiscard,
): AdvanceResult {
    val decider = entry.resolutionController
    val drawn = drawCards(state, decider, clause.drawCount)
    // CR 601.2c: discard as many as told, but no more than the hand holds (a small library may leave fewer).
    val count = minOf(clause.discardCount, drawn.player(decider).hand.size)
    if (count == 0) return completeClauseResolution(drawn, entry)
    val paused = drawn.copy(pendingResolutionDiscard = PendingResolutionDiscard(decider, count))
    return AdvanceResult.NeedsDecision(paused, pendingResolutionDiscardRequest(paused))
}

/**
 * The mandatory-discard request the open [GameState.pendingResolutionDiscard] is waiting on (CR 601.2c): the
 * decider's whole hand, of which exactly the pending count must be chosen. Pure per ADR-004.
 */
internal fun pendingResolutionDiscardRequest(state: GameState): DecisionRequest.ChooseResolutionDiscards {
    val pending = state.pendingResolutionDiscard ?: error("no resolution discard is pending")
    val hand = state.player(pending.decider).hand
    return DecisionRequest.ChooseResolutionDiscards(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        options = hand.map { DecisionRequest.ChooseResolutionDiscards.Option(it.id, it.card) },
        count = pending.count,
    )
}

/**
 * Applies the mandatory discard selection (CR 601.2c, CR 701.8): discards each of [objectIds] through the
 * CR 614/616 framework (so a madness card is exiled instead — the flagship Looting-into-Fiery-Temper line),
 * then the resolving spell leaves the stack.
 */
internal fun applyResolutionDiscards(
    state: GameState,
    objectIds: List<ObjectId>,
): AdvanceResult {
    val pending = state.pendingResolutionDiscard ?: error("no resolution discard is pending")
    val entry = resolvingClauseEntry(state)
    val cleared = state.copy(pendingResolutionDiscard = null)
    val discarded = objectIds.fold(cleared) { current, id -> discardApplyingReplacements(current, pending.decider, id) }
    return completeClauseResolution(discarded, entry)
}
