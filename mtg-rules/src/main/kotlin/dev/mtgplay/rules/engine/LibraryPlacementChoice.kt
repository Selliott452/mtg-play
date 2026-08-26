package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.LibraryPosition
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingLibraryPlacement
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.resolutionController
import dev.mtgplay.core.state.resolutionSourceCard
import dev.mtgplay.core.state.resolutionTargets
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.effect.putPermanentIntoOwnersLibrary

/*
 * The "the **owner** of target nonland permanent puts it into their library second from the top or on
 * the bottom" clause (CR 401.1, CR 108.3) — Deem Inferior's whole effect. Additive (`W9-F`), a member of
 * the `FW-CLAUSEHOOK` family (docs/design/resolution-clause-hook.md): orchestrate → request → apply.
 *
 * **Two independent reasons it is a clause**, and the second is the one the family exists for: the depth
 * is a decision (ADR-004 keeps decisions out of resolution effects), and the deciding seat is the
 * permanent's **owner** — a third reading of "somebody other than the controller", after an opponent of
 * the controller (the each-opponent discard) and a targeted player (the graveyard exile). Ownership is
 * fixed for the game (CR 108.3) while control can change hands, so this is not a rename of either.
 *
 * **Nothing here is hidden (ADR-007).** The permanent, its owner and the two depths are public
 * (CR 400.2); what ends up hidden is the *result*, a card at a known depth in a library, and no seat view
 * shows library contents to anybody. Contrast the library search, whose **options** are library cards.
 *
 * **Neither option can ever be unavailable**, which makes this the only pause in the family with no
 * "cannot be done" pre-check: a library always accepts a card, and an empty one accepts "second from the
 * top" by seating it on top, the only reading that exists when there is no first card.
 */

/**
 * Runs the owner-chooses-a-library-position clause of the resolving [entry] (CR 401.1): pauses for the
 * targeted permanent's **owner** to name a depth.
 *
 * Called by the clause hook after the object's (deliberately empty) ordinary effect — the permanent is
 * still on the battlefield, which is what lets the owner be read live rather than as last-known
 * information. A definition carrying this clause without a one-permanent
 * [dev.mtgplay.core.definition.TargetSpec.TargetPermanent] spec is a card defect and fails loudly.
 */
internal fun orchestrateLibraryPlacement(
    state: GameState,
    entry: StackEntry,
): AdvanceResult {
    val target =
        entry.resolutionTargets.singleOrNull() as? Target.Permanent
            ?: error(
                "CR 115.1b: an owner-library-placement clause needs exactly one permanent target, " +
                    "got ${entry.resolutionTargets}",
            )
    // CR 108.3: the owner is a property of the card and is what the printed line names; the CR 608.2b
    // re-check has just confirmed the permanent is here, so a missing one is an engine defect.
    val owner =
        state.sharedZones.battlefield
            .firstOrNull { it.id == target.id }
            ?.owner
            ?: error("CR 608.2b: ${entry.resolutionSourceCard.name}'s target ${target.id} is not on the battlefield")
    val paused =
        state.copy(
            pendingLibraryPlacement =
                PendingLibraryPlacement(
                    decider = owner,
                    permanent = target.id,
                    sourceCard = entry.resolutionSourceCard,
                ),
        )
    return AdvanceResult.NeedsDecision(paused, pendingLibraryPlacementRequest(paused))
}

/**
 * The depth request the open [GameState.pendingLibraryPlacement] is waiting on (CR 401.1). Pure per
 * ADR-004: the two options are a closed vocabulary and the permanent's identity is read from the
 * battlefield, which cannot change while the pause is open.
 */
internal fun pendingLibraryPlacementRequest(state: GameState): DecisionRequest.ChooseLibraryPosition {
    val pending = state.pendingLibraryPlacement ?: error("no library placement is pending")
    val entry = resolvingClauseEntry(state)
    val permanent =
        state.sharedZones.battlefield
            .firstOrNull { it.id == pending.permanent }
            ?: error(
                "CR 401.1: the permanent ${pending.permanent} awaiting a library position " +
                    "is not on the battlefield",
            )
    return DecisionRequest.ChooseLibraryPosition(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        controller = entry.resolutionController,
        sourceCard = pending.sourceCard,
        permanent = pending.permanent,
        permanentCard = permanent.card,
        options = LibraryPosition.entries.toList(),
    )
}

/**
 * Applies the chosen depth (CR 401.1, CR 400.7): the permanent leaves the battlefield for its owner's
 * library at [position] — with every consequence of leaving the battlefield, which
 * [putPermanentIntoOwnersLibrary] owns — and the resolving object then finishes through the shared
 * [completeClauseResolution].
 */
internal fun applyLibraryPlacement(
    state: GameState,
    position: LibraryPosition,
): AdvanceResult {
    val pending = state.pendingLibraryPlacement ?: error("no library placement is pending")
    val entry = resolvingClauseEntry(state)
    val cleared = state.copy(pendingLibraryPlacement = null)
    return completeClauseResolution(putPermanentIntoOwnersLibrary(cleared, pending.permanent, position), entry)
}
