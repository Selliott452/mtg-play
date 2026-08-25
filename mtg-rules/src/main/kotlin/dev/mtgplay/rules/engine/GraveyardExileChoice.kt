package dev.mtgplay.rules.engine

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingGraveyardExile
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.resolutionController
import dev.mtgplay.core.state.resolutionSourceCard
import dev.mtgplay.core.state.resolutionTargets
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.effect.exileCardFromGraveyard

/*
 * The "target player exiles a card from their graveyard" clause (CR 701.3a, CR 404) — Relic of
 * Progenitus' "{T}: Target player exiles a card from their graveyard." Additive (`W8-D`), a member of
 * the `FW-CLAUSEHOOK` family (docs/design/resolution-clause-hook.md): orchestrate → request → apply.
 *
 * **The whole reason it is a clause is the decider.** Exiling one graveyard card has been a published
 * primitive since `FW-MULTITGT`; what a [dev.mtgplay.core.definition.ResolutionEffect] cannot do is ask
 * a player which card, and CR 701.3a puts the choice on the *targeted* player — the player who performs
 * an action makes its choices. That is a second [dev.mtgplay.rules.decision.DecisionRequest] whose seat
 * is not the resolving object's controller, after the each-opponent discard, and the first whose seat is
 * named by one of the object's own targets and may therefore *be* the controller.
 *
 * **The chosen card is not a target and is never re-checked.** The CR 608.2b re-check ran on the
 * *player* before this clause was entered; the card is picked afterwards, from whatever is in that
 * player's graveyard at that moment. An opponent who empties their graveyard in response has not made
 * the ability fizzle — it resolves and exiles nothing.
 *
 * **An empty graveyard is asked nothing.** A request with no options would be an enumerated decision
 * with no legal answer (ADR-005), so the clause completes the resolution straight away instead; the
 * printed line is mandatory, not a "may", so there is no decline option to fall back on either.
 */

/**
 * Runs the target-player-exiles clause of the resolving [entry] (CR 701.3a): pauses for the **targeted**
 * player to choose one of their own graveyard cards, or — with an empty graveyard — finishes the
 * resolution now.
 *
 * Called by the clause hook after the object's ordinary effect. A definition carrying this clause
 * without a [dev.mtgplay.core.definition.TargetSpec.TargetPlayer] spec is a card defect and fails loudly
 * rather than silently exiling nobody's card.
 */
internal fun orchestrateGraveyardExileChoice(
    state: GameState,
    entry: StackEntry,
): AdvanceResult {
    val target =
        entry.resolutionTargets.singleOrNull() as? Target.Player
            ?: error(
                "CR 115.1a: a target-player-exiles clause needs exactly one player target, " +
                    "got ${entry.resolutionTargets}",
            )
    if (state.player(target.id).graveyard.isEmpty()) return completeClauseResolution(state, entry)
    val paused =
        state.copy(
            pendingGraveyardExile =
                PendingGraveyardExile(decider = target.id, sourceCard = entry.resolutionSourceCard),
        )
    return AdvanceResult.NeedsDecision(paused, pendingGraveyardExileRequest(paused))
}

/**
 * The exile-one-card request the open [GameState.pendingGraveyardExile] is waiting on (CR 701.3a). Pure
 * per ADR-004: the decider's graveyard, in the zone's own (bottom-first) order, re-derived from the
 * state rather than captured — nothing moves while the pause is open, so the two agree by construction.
 *
 * The controller is read off the resolving object for display; the option cards need no per-seat
 * filtering, since a graveyard is public (CR 400.2).
 */
internal fun pendingGraveyardExileRequest(state: GameState): DecisionRequest.ChooseGraveyardCardToExile {
    val pending = state.pendingGraveyardExile ?: error("no graveyard exile choice is pending")
    val entry = resolvingClauseEntry(state)
    return DecisionRequest.ChooseGraveyardCardToExile(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        controller = entry.resolutionController,
        sourceCard = pending.sourceCard,
        options =
            state
                .player(pending.decider)
                .graveyard
                .map { DecisionRequest.ChooseGraveyardCardToExile.Option(it.id, it.card) },
    )
}

/**
 * Applies the exile choice (CR 701.3a): [objectId] leaves its owner's graveyard for exile as a new
 * object (CR 400.7), then the resolving object finishes through the shared [completeClauseResolution].
 */
internal fun applyGraveyardExileChoice(
    state: GameState,
    objectId: ObjectId,
): AdvanceResult {
    state.pendingGraveyardExile ?: error("no graveyard exile choice is pending")
    val entry = resolvingClauseEntry(state)
    val cleared = state.copy(pendingGraveyardExile = null)
    return completeClauseResolution(exileCardFromGraveyard(cleared, objectId), entry)
}
