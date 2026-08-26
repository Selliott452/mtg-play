package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.EachOpponentSacrifices
import dev.mtgplay.core.definition.SacrificeNarrowing
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingOpponentSacrifice
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.resolutionClauses
import dev.mtgplay.core.state.resolutionController
import dev.mtgplay.core.state.resolutionOptionalCostPaid
import dev.mtgplay.core.state.resolutionSourceCard
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import kotlinx.collections.immutable.toPersistentList

/*
 * The "each opponent sacrifices a permanent of their choice" flow (`FW-NONCTRLDEC`, `W9-B`) — Extract a
 * Confession.
 *
 * The sibling of `OpponentDiscardFlow.kt`, and deliberately written as its mirror: the same APNAP queue
 * (CR 101.4), the same skip-whoever-cannot rule, the same "the decider is not the controller" shape. Two
 * things differ, and both are worth stating because each looks like an omission and is not.
 *
 * **1. No secrecy problem.** The discard flow's whole design question was ADR-007: its options are the
 * decider's hidden hand (CR 402.1), so the controller gets a count-only projection. These options are
 * battlefield permanents, which every seat can already see (CR 400.2). So there is no
 * `PendingOpponentSacrificeView`, no seat-view field, and no leak test to write — publishing a projection
 * of public information would imply an asymmetry that does not exist.
 *
 * **2. The choice can be narrowed by a cost paid while casting.** Extract a Confession prints "each
 * opponent sacrifices a creature of their choice. If evidence was collected, instead each opponent
 * sacrifices a creature with the greatest power among creatures they control." That is one clause with
 * two enumerations, selected by the resolving spell's CR 601.2b linked information — the first time in
 * the engine that a mid-resolution *option list* depends on a cost announced a whole stage earlier. It is
 * read once, as the clause begins ([PendingOpponentSacrifice.greatestPowerOnly]), so every opponent in
 * the queue answers the same question and the question cannot drift.
 *
 * **The narrowing filters and never collapses.** With two 3/3s tied at the top the opponent still
 * chooses between them, and the choice is real — one may be enchanted, one may be the blocker they need.
 * An engine that picked would delete a line of play (ADR-005); an engine that offered the 1/1 would
 * enumerate an illegal one.
 */

/**
 * Runs an "each opponent sacrifices a permanent of their choice" [clause] (CR 701.17a): asks the
 * resolving object's opponents in APNAP order, skipping any who control no matching permanent.
 *
 * The narrowing is settled here, once, from [entry]'s linked cost information (CR 601.2b).
 */
internal fun orchestrateEachOpponentSacrifices(
    state: GameState,
    entry: StackEntry,
    clause: EachOpponentSacrifices,
): AdvanceResult {
    val narrowing =
        if (entry.resolutionOptionalCostPaid) clause.narrowingWhenOptionalCostPaid else clause.narrowing
    return advanceOpponentSacrificeQueue(
        state = state,
        entry = entry,
        clause = clause,
        greatestPowerOnly = narrowing == SacrificeNarrowing.GREATEST_POWER,
        queue = opponentsInApnapOrder(state, entry.resolutionController),
    )
}

/**
 * Asks the next opponent in [queue] who can sacrifice, or — when none remain — completes the resolution
 * (CR 701.17a). The controller is read off [entry] rather than passed in, so it cannot disagree with the
 * one the clause is resolving for.
 *
 * The single place the queue advances, shared by the initial orchestration and by every resume after an
 * opponent answers, so "skip whoever cannot sacrifice" is written once and cannot disagree with itself.
 * An opponent who controls no matching permanent is skipped silently: CR 701.17a makes an impossible
 * sacrifice simply not happen, and there is no rider on this clause to pay the controller for it.
 */
private fun advanceOpponentSacrificeQueue(
    state: GameState,
    entry: StackEntry,
    clause: EachOpponentSacrifices,
    greatestPowerOnly: Boolean,
    queue: List<PlayerId>,
): AdvanceResult {
    val controller = entry.resolutionController
    // CR 701.17a: an opponent who controls no matching permanent simply cannot sacrifice; never asked.
    val skipped = queue.takeWhile { sacrificeOptionsFor(state, it, clause, greatestPowerOnly).isEmpty() }
    val remaining = queue.drop(skipped.size)
    val next = remaining.firstOrNull()
    if (next == null) return completeClauseResolution(state, entry)
    val paused =
        state.copy(
            pendingOpponentSacrifice =
                PendingOpponentSacrifice(
                    decider = next,
                    controller = controller,
                    greatestPowerOnly = greatestPowerOnly,
                    remaining = remaining.drop(1).toPersistentList(),
                    sourceCard = entry.resolutionSourceCard,
                ),
        )
    return AdvanceResult.NeedsDecision(paused, pendingOpponentSacrificeRequest(paused))
}

/**
 * The opponent-sacrifice request the open [GameState.pendingOpponentSacrifice] is waiting on
 * (CR 701.17a): the deciding opponent's own matching permanents, already narrowed if the clause says so.
 * Pure per ADR-004 — the option list is re-derived from the battlefield rather than stored, so a
 * permanent that left in between simply is not offered.
 */
internal fun pendingOpponentSacrificeRequest(state: GameState): DecisionRequest.ChooseOpponentSacrifice {
    val pending = state.pendingOpponentSacrifice ?: error("no opponent sacrifice is pending")
    val clause =
        resolvingOpponentSacrificeClause(state)
            ?: error("CR 701.17a: an open opponent sacrifice belongs to an each-opponent-sacrifices clause")
    return DecisionRequest.ChooseOpponentSacrifice(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        controller = pending.controller,
        sourceCard = pending.sourceCard,
        greatestPowerOnly = pending.greatestPowerOnly,
        options =
            sacrificeOptionsFor(state, pending.decider, clause, pending.greatestPowerOnly)
                .map { DecisionRequest.ChooseOpponentSacrifice.Option(it.id, it.card) },
    )
}

/**
 * Applies one opponent's sacrifice choice (CR 701.17a, CR 701.17): sacrifices the chosen permanent, then
 * moves on to the next opponent, or finishes the clause.
 */
internal fun applyOpponentSacrifice(
    state: GameState,
    objectId: ObjectId,
): AdvanceResult {
    val pending = state.pendingOpponentSacrifice ?: error("no opponent sacrifice is pending")
    val entry = resolvingClauseEntry(state)
    val clause =
        resolvingOpponentSacrificeClause(state)
            ?: error("CR 701.17a: an open opponent sacrifice belongs to an each-opponent-sacrifices clause")
    val cleared = state.copy(pendingOpponentSacrifice = null)
    val sacrificed = sacrificePermanents(cleared, pending.decider, listOf(objectId))
    return advanceOpponentSacrificeQueue(
        state = sacrificed,
        entry = entry,
        clause = clause,
        greatestPowerOnly = pending.greatestPowerOnly,
        queue = pending.remaining,
    )
}

/**
 * The permanents [seat] could sacrifice to [clause] right now (CR 701.17a): the matching permanents they
 * control, narrowed to the greatest-power ones when [greatestPowerOnly].
 *
 * Power is the **effective** power (CR 613), so a creature carrying a `+1/+1` counter or wearing an Aura
 * is compared as it actually is — comparing printed power would offer a 1/1 Slippery Bogle under an
 * Ethereal Armor as though it were the smallest creature on the board, which is a wrong option list and
 * not merely a cosmetic one.
 */
private fun sacrificeOptionsFor(
    state: GameState,
    seat: PlayerId,
    clause: EachOpponentSacrifices,
    greatestPowerOnly: Boolean,
): List<GameObject> {
    val matching =
        state.sharedZones.battlefield.filter { obj ->
            val types = state.definitions[obj.card]?.characteristics?.cardTypes ?: emptySet()
            obj.owner == seat && clause.cardType in types
        }
    if (!greatestPowerOnly || matching.isEmpty()) return matching
    val greatest = matching.maxOf { effectivePower(state, it.id) }
    return matching.filter { effectivePower(state, it.id) == greatest }
}

/** The each-opponent-sacrifices clause of the object an open pause belongs to (CR 608.1), or `null`. */
private fun resolvingOpponentSacrificeClause(state: GameState): EachOpponentSacrifices? =
    state.sharedZones.stack
        .lastOrNull()
        ?.resolutionClauses
        ?.eachOpponentSacrifices
