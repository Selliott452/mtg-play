package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.PermanentSelection
import dev.mtgplay.core.definition.PermanentSelectionAction
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingPermanentSelection
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.resolutionClauses
import dev.mtgplay.core.state.resolutionController
import dev.mtgplay.core.state.resolutionSourceCard
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.effect.returnPermanentToOwnersHand
import dev.mtgplay.rules.effect.untapPermanent

/*
 * The **untargeted** mid-resolution permanent-selection clause (CR 609.4) — Snap's "Untap up to two
 * lands" and Azorius Chancery's "return a land you control to its owner's hand". Additive, flagged
 * (`FW-TAPUNTAP`).
 *
 * The clause runs after the resolving object's ordinary effect, pauses for the choice, performs the
 * declared action on each chosen permanent, and completes the resolution — the shape every
 * [dev.mtgplay.core.definition.ResolutionClauses] member takes (`FW-CLAUSEHOOK`), so a spell (Snap) and
 * a triggered ability (the Chancery's) run the same code and differ only in how they leave the stack.
 *
 * **Why this is a clause rather than a target spec.** Neither card prints the word "target", so the
 * permanents are chosen as the object resolves (CR 609.4) rather than as it is put on the stack. That
 * is observable three ways — hexproof does not subtract from the options (CR 702.11a), there is no
 * CR 608.2b re-check and so no fizzle, and nobody may respond to the choice — and
 * [PermanentSelection]'s KDoc records all three. This file is where the first of them is *implemented*:
 * the option list comes from [matchingPermanents], which knows nothing about `targetableBy`.
 */

/**
 * Runs a resolving object's [clause] (CR 609.4): pauses for the [PendingPermanentSelection] choice over
 * the matching battlefield permanents, or — when the board offers none — completes the resolution
 * immediately, the clause having chosen nothing.
 *
 * The bounds are **clamped to what the board offers** before the pause opens. Snap's `0..2` with one
 * land on the battlefield becomes `0..1`, and the Chancery's mandatory `1..1` with no land at all
 * becomes no pause and no return — the same clamp a targeting line gets, and the reason the mandatory
 * case cannot demand a permanent that does not exist.
 *
 * The resolving [entry] stays on top of the stack throughout, which is what makes the pending selection
 * a pure derivation of the paused state (ADR-004).
 */
internal fun orchestratePermanentSelection(
    state: GameState,
    entry: StackEntry,
    clause: PermanentSelection,
): AdvanceResult {
    val decider = entry.resolutionController
    val available = matchingPermanents(state, clause.filter, decider).size
    if (available == 0) return completeClauseResolution(state, entry)
    val paused =
        state.copy(
            pendingPermanentSelection =
                PendingPermanentSelection(
                    decider = decider,
                    action = clause.action,
                    minimum = minOf(clause.minimum, available),
                    maximum = minOf(clause.maximum, available),
                ),
        )
    return AdvanceResult.NeedsDecision(paused, pendingPermanentSelectionRequest(paused))
}

/**
 * The selection request the open [GameState.pendingPermanentSelection] is waiting on (CR 609.4): every
 * battlefield permanent matching the resolving object's clause, of which between the pending minimum
 * and maximum must be chosen. A pure function of the state (ADR-004).
 *
 * The filter is re-read from the **resolving object's own declaration** rather than stored on the
 * pending record, so the option list here and the one the pause was opened against are derived by one
 * expression. Nothing can change between them — the whole clause is one transition — but deriving them
 * twice from one source is what keeps that true rather than merely believed.
 */
internal fun pendingPermanentSelectionRequest(state: GameState): DecisionRequest.ChoosePermanentsToAffect {
    val pending = state.pendingPermanentSelection ?: error("no permanent selection is pending")
    val entry = resolvingClauseEntry(state)
    val clause =
        entry.resolutionClauses.permanentSelection
            ?: error("CR 609.4: the resolving ${entry.resolutionSourceCard.name} declares no permanent selection")
    val options = matchingPermanents(state, clause.filter, pending.decider)
    return DecisionRequest.ChoosePermanentsToAffect(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        sourceCard = entry.resolutionSourceCard,
        prompt = permanentSelectionPrompt(pending.action, pending.minimum, pending.maximum),
        options = options.map { DecisionRequest.ChoosePermanentsToAffect.Option(it.id, it.card) },
        minimumCount = pending.minimum,
        maximumCount = pending.maximum,
    )
}

/**
 * Applies a permanent-selection answer (CR 609.4): performs the pending action on each of [objectIds],
 * in the order chosen, then the resolving object leaves the stack.
 *
 * The permanents are affected **one at a time in selection order**, which is deterministic and
 * replay-stable (ADR-006). Neither action in the pool can make a later one in the same batch impossible
 * — untapping is independent per permanent, and a return names distinct battlefield objects — so no
 * intermediate check is needed; both primitives are no-ops on an object that is not on the battlefield
 * anyway, which is the honest behaviour if a future action ever does interfere.
 */
internal fun applyPermanentSelection(
    state: GameState,
    objectIds: List<ObjectId>,
): AdvanceResult {
    val pending = state.pendingPermanentSelection ?: error("no permanent selection is pending")
    val entry = resolvingClauseEntry(state)
    val cleared = state.copy(pendingPermanentSelection = null)
    val affected =
        objectIds.fold(cleared) { current, id ->
            when (pending.action) {
                PermanentSelectionAction.UNTAP -> untapPermanent(current, id)
                PermanentSelectionAction.RETURN_TO_OWNERS_HAND -> returnPermanentToOwnersHand(current, id)
            }
        }
    return completeClauseResolution(affected, entry)
}

/** A short human description of a pending selection, for display (ADR-005 — what the indices mean). */
private fun permanentSelectionPrompt(
    action: PermanentSelectionAction,
    minimum: Int,
    maximum: Int,
): String {
    val verb =
        when (action) {
            PermanentSelectionAction.UNTAP -> "untap"
            PermanentSelectionAction.RETURN_TO_OWNERS_HAND -> "return to its owner's hand"
        }
    val count = if (minimum == maximum) "exactly $maximum" else "up to $maximum"
    return "Choose $count permanent(s) to $verb"
}
