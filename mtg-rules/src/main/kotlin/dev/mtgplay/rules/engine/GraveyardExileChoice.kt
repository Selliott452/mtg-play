package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.OptionalGraveyardExileGate
import dev.mtgplay.core.definition.ResolutionContext
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingGraveyardExile
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.resolutionClauses
import dev.mtgplay.core.state.resolutionController
import dev.mtgplay.core.state.resolutionSourceCard
import dev.mtgplay.core.state.resolutionSourceId
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
        options = exilableGraveyardCards(state, pending),
        optionalExile = pending.optional,
    )
}

/**
 * The deciding player's graveyard cards this pause may exile (CR 404), in graveyard (bottom-first)
 * order — every card for a clause with no filter, and only the matching ones for a filtered "you may
 * exile a **creature** card" (Masked Vandal).
 */
private fun exilableGraveyardCards(
    state: GameState,
    pending: PendingGraveyardExile,
): List<DecisionRequest.ChooseGraveyardCardToExile.Option> {
    val restriction = pending.restriction
    return state
        .player(pending.decider)
        .graveyard
        .filter { restriction == null || satisfiesGraveyardCardRestriction(state, restriction, it) }
        .map { DecisionRequest.ChooseGraveyardCardToExile.Option(it.id, it.card) }
}

/**
 * Applies the exile choice (CR 701.3a, CR 404): [objectId] leaves its owner's graveyard for exile as a
 * new object (CR 400.7) — or, for a declined "you may exile" ([objectId] `null`), nothing leaves at all
 * — and the resolving object then finishes through the shared [completeClauseResolution].
 *
 * **The "if you do" half runs here, and only on the branch that exiled.** A resolving object declaring
 * [dev.mtgplay.core.definition.OptionalGraveyardExileGate] left its ordinary
 * [dev.mtgplay.core.definition.ResolutionEffect] empty precisely so this gate could withhold the gated
 * effect, so the gated effect is performed at this point and never before (Masked Vandal). An object
 * declaring the mandatory clause has no gated half and reaches [completeClauseResolution] directly.
 */
internal fun applyGraveyardExileChoice(
    state: GameState,
    objectId: ObjectId?,
): AdvanceResult {
    val pending = state.pendingGraveyardExile ?: error("no graveyard exile choice is pending")
    require(pending.optional || objectId != null) {
        "CR 701.3a: a mandatory graveyard exile names a card, but this choice named none"
    }
    val entry = resolvingClauseEntry(state)
    val cleared = state.copy(pendingGraveyardExile = null)
    if (objectId == null) return completeClauseResolution(cleared, entry)
    val exiled = exileCardFromGraveyard(cleared, objectId)
    val gate = entry.resolutionClauses.optionalGraveyardExileGate
    val gated = if (gate == null) exiled else gate.thenEffect.resolve(exiled, gatedContext(entry))
    return completeClauseResolution(gated, entry)
}

/**
 * The [dev.mtgplay.core.definition.ResolutionContext] a gated "if you do" effect resolves against — the
 * same one the ordinary effect would have received, so a gated effect reads its object's CR 603.3d
 * targets and CR 113.7c source exactly as an ungated one does.
 */
private fun gatedContext(entry: StackEntry): ResolutionContext =
    ResolutionContext(
        controller = entry.resolutionController,
        targets = entry.resolutionTargets,
        source = entry.resolutionSourceId,
        sourceCard = entry.resolutionSourceCard,
    )

/**
 * Runs the optional graveyard-exile gate of the resolving [entry] (CR 404, CR 608.2c): pauses for the
 * controller to name one matching card in their own graveyard or decline, and — on the decline branch,
 * or with no matching card at all — completes the resolution having done nothing.
 *
 * Called by the clause hook after the object's (deliberately empty) ordinary effect. With no matching
 * card the "you may" has no yes branch, so nothing is asked: a request whose only answer is "no" is not
 * a decision (ADR-005).
 */
internal fun orchestrateOptionalGraveyardExile(
    state: GameState,
    entry: StackEntry,
    gate: OptionalGraveyardExileGate,
): AdvanceResult {
    val decider = entry.resolutionController
    val paused =
        state.copy(
            pendingGraveyardExile =
                PendingGraveyardExile(
                    decider = decider,
                    sourceCard = entry.resolutionSourceCard,
                    optional = true,
                    restriction = gate.restriction,
                ),
        )
    if (pendingGraveyardExileRequest(paused).options.isEmpty()) return completeClauseResolution(state, entry)
    return AdvanceResult.NeedsDecision(paused, pendingGraveyardExileRequest(paused))
}
