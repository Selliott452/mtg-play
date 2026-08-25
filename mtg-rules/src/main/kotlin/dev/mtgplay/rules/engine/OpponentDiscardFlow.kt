package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.EachOpponentDiscards
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingOpponentDiscard
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.resolutionClauses
import dev.mtgplay.core.state.resolutionController
import dev.mtgplay.core.state.resolutionSourceCard
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.effect.drawCards
import kotlinx.collections.immutable.toPersistentList

/*
 * The "each opponent discards a card; for each opponent who can't, you draw a card" flow
 * (`FW-NONCTRLDEC`, docs/design/exile-and-return.md §6) — Refurbished Familiar.
 *
 * **The ADR-005 + ADR-007 question, and the answer.** Every other mid-resolution pause in the engine is
 * answered by the resolving object's controller. This one is answered by that controller's *opponent*,
 * over the opponent's *own hand* — a hidden zone (CR 402.1) the controller may not see. Two rules meet:
 * ADR-005 says the engine must enumerate the legal options, and ADR-007 says a seat is shown only what
 * it may know.
 *
 * They do not actually conflict, and the reason is structural rather than a new mechanism. A
 * `DecisionRequest` is addressed to exactly one seat — [DecisionRequestId.seat] — and is delivered to
 * that seat alone. So enumerating the discarding opponent's whole hand inside their own request leaks
 * nothing: the controller is never handed the object. What the controller is handed is the seat view,
 * and there the pause appears only as a **count** ([dev.mtgplay.core.state.PendingOpponentDiscard]
 * projected without its options), which is precisely the treatment a private library look (CR 701.14a)
 * already gets. The rule the acceptance suite pins is therefore: *the enumerated options of a decision
 * belong to `id.seat` and to no other seat*, and `ViewLeakPropertySpec` checks it as a property rather
 * than as a scenario.
 *
 * **The queue.** "Each opponent" is one clause producing one decision *per opponent*, while
 * [AdvanceResult] surfaces one request at a time — so the clause walks the opponents in APNAP order
 * (CR 101.4), and an opponent who cannot discard is skipped rather than asked, accumulating the
 * controller's draw instead (CR 701.7a). The pool is two-player, so the queue is always empty in a real
 * game; it is modelled anyway so the printed "each opponent" is not quietly a "target opponent".
 */

/**
 * Runs an "each opponent discards a card" [clause] (CR 701.7a): asks the resolving object's opponents in
 * APNAP order, skipping any who cannot discard and accumulating the controller's draw for each.
 */
internal fun orchestrateEachOpponentDiscards(
    state: GameState,
    entry: StackEntry,
    clause: EachOpponentDiscards,
): AdvanceResult =
    advanceOpponentDiscardQueue(
        state = state,
        entry = entry,
        clause = clause,
        queue = opponentsInApnapOrder(state, entry.resolutionController),
        drawsOwed = 0,
    )

/**
 * Asks the next opponent in [queue] who can discard, or — when none remain — draws [drawsOwed] cards for
 * the resolving object's controller (CR 701.7a) and completes the resolution. The controller is read off
 * [entry] rather than passed in, so it cannot disagree with the one the clause is resolving for.
 *
 * The single place the queue advances, shared by the initial orchestration and by every resume after an
 * opponent answers, so "skip whoever cannot discard" is written once and cannot disagree with itself.
 */
private fun advanceOpponentDiscardQueue(
    state: GameState,
    entry: StackEntry,
    clause: EachOpponentDiscards,
    queue: List<PlayerId>,
    drawsOwed: Int,
): AdvanceResult {
    val controller = entry.resolutionController
    // CR 701.7a: an opponent with no cards in hand simply cannot discard; they are never asked.
    val skipped = queue.takeWhile { state.player(it).hand.isEmpty() }
    val remaining = queue.drop(skipped.size)
    val owed = drawsOwed + skipped.size * clause.drawPerOpponentWhoCannot
    val next = remaining.firstOrNull()
    if (next == null) {
        val drawn = if (owed > 0) drawCards(state, controller, owed) else state
        return completeClauseResolution(drawn, entry)
    }
    val paused =
        state.copy(
            pendingOpponentDiscard =
                PendingOpponentDiscard(
                    decider = next,
                    controller = controller,
                    // CR 701.7a: discard as many as told, but no more than the hand holds.
                    count = minOf(clause.count, state.player(next).hand.size),
                    remaining = remaining.drop(1).toPersistentList(),
                    drawsOwed = owed,
                    sourceCard = entry.resolutionSourceCard,
                ),
        )
    return AdvanceResult.NeedsDecision(paused, pendingOpponentDiscardRequest(paused))
}

/**
 * The opponent-discard request the open [GameState.pendingOpponentDiscard] is waiting on (CR 701.7a):
 * the **deciding opponent's own hand**, of which exactly the pending count must be chosen. Pure per
 * ADR-004.
 *
 * This is the function that would leak if the ADR-007 ruling were wrong, so it is worth being explicit
 * about why it does not: the hand it enumerates belongs to [DecisionRequestId.seat] itself, and the
 * request is delivered only there. Nothing about the controller's view is derived from this object.
 */
internal fun pendingOpponentDiscardRequest(state: GameState): DecisionRequest.ChooseOpponentDiscards {
    val pending = state.pendingOpponentDiscard ?: error("no opponent discard is pending")
    val hand = state.player(pending.decider).hand
    return DecisionRequest.ChooseOpponentDiscards(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        controller = pending.controller,
        sourceCard = pending.sourceCard,
        options = hand.map { DecisionRequest.ChooseOpponentDiscards.Option(it.id, it.card) },
        count = pending.count,
    )
}

/**
 * Applies one opponent's discard selection (CR 701.7a, CR 701.8): discards each of [objectIds] through
 * the CR 614/616 framework — so an opponent's madness card discarded to Refurbished Familiar is exiled
 * instead and its reflexive cast fires, for **that opponent**, which is the correct and slightly
 * surprising interaction — then moves on to the next opponent, or finishes the clause.
 */
internal fun applyOpponentDiscards(
    state: GameState,
    objectIds: List<ObjectId>,
): AdvanceResult {
    val pending = state.pendingOpponentDiscard ?: error("no opponent discard is pending")
    val entry = resolvingClauseEntry(state)
    val cleared = state.copy(pendingOpponentDiscard = null)
    val discarded =
        objectIds.fold(cleared) { current, id -> discardApplyingReplacements(current, pending.decider, id) }
    val clause =
        entry.resolutionClauses.eachOpponentDiscards
            ?: error("CR 701.7a: an open opponent discard belongs to an each-opponent-discards clause")
    return advanceOpponentDiscardQueue(
        state = discarded,
        entry = entry,
        clause = clause,
        queue = pending.remaining,
        drawsOwed = pending.drawsOwed,
    )
}

/**
 * The [controller]'s opponents in APNAP order (CR 101.4): the active player first if they are one, then
 * the rest in seating order. The order two opponents are asked in is observable — each sees the other's
 * discard before choosing in a game with three or more players — so it is fixed by rule rather than by
 * map iteration.
 */
private fun opponentsInApnapOrder(
    state: GameState,
    controller: PlayerId,
): List<PlayerId> {
    val seats = state.players.keys.toList()
    val start = seats.indexOf(state.turn.activePlayer).coerceAtLeast(0)
    return (seats.indices)
        .map { seats[(start + it) % seats.size] }
        .filter { it != controller }
}
