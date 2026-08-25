package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.ChosenColorEffect
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingChosenColor
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.resolutionClauses
import dev.mtgplay.core.state.resolutionController
import dev.mtgplay.core.state.resolutionSourceCard
import dev.mtgplay.core.state.resolutionSourceId
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.effect.preventDamageFromColorThisTurn

/*
 * The "choose a colour, then do something with it" resolution clause (CR 609.4) — Prismatic Strands.
 * Additive (`FW-PREVENT2`), and a member of the `FW-CLAUSEHOOK` family: one decision, then the chosen
 * colour is consumed, then the resolving object leaves the stack through the shared
 * [completeClauseResolution].
 *
 * **The simplest clause in the family to enumerate, and the only one that cannot fail.** Every sibling
 * draws its options from a zone or the battlefield, so each needs a "is there anything to choose?"
 * pre-check and each can have an empty option list on some board. This one's options are the five
 * colours (CR 105.1) — the same five on every board, in WUBRG order — so there is no board on which the
 * clause is unrunnable and nothing to pre-check.
 *
 * **Reusing [DecisionRequest.ChooseColor] rather than adding a kind.** The request Utopia Sprawl's
 * CR 614.12 as-enters choice defined is *exactly* this payload: five colours, answered by index. The
 * two flows are told apart by which pending record is open, which is the disambiguation five yes/no
 * flows already rely on ([applyChosenYesNo]). What differs between them is the resume path, not the
 * question — and the resume path is not part of a request.
 */

/**
 * Runs the chosen-colour clause of the resolving [entry] (CR 609.4): pauses for its controller's colour
 * choice. Called by the clause hook after the object's ordinary effect, while it is still on top of the
 * stack.
 *
 * It takes no clause argument, unlike its siblings: what to do with the colour is re-derived from the
 * resolving object when the answer arrives (ADR-004), so passing the declaration in here would put a
 * second copy of it on a path that does not use it.
 */
internal fun orchestrateChosenColor(
    state: GameState,
    entry: StackEntry,
): AdvanceResult {
    val paused = state.copy(pendingChosenColor = PendingChosenColor(decider = entry.resolutionController))
    return AdvanceResult.NeedsDecision(paused, pendingChosenColorRequest(paused))
}

/**
 * The colour choice the open chosen-colour clause is waiting on (CR 609.4). A pure function of the
 * state (ADR-004) — the resolving object is the top of the stack.
 *
 * All five colours are offered unconditionally, and that is the rule rather than a simplification:
 * CR 609.4 lets a player choose any colour, whether or not anything on the board is that colour. A
 * Prismatic Strands naming a colour the opponent is not playing is a legal, occasionally deliberate
 * play, and filtering the list to "colours present" would delete it from the action space (ADR-005).
 */
internal fun pendingChosenColorRequest(state: GameState): DecisionRequest.ChooseColor {
    val pending = state.pendingChosenColor ?: error("no chosen-colour clause is pending")
    val entry = resolvingClauseEntry(state)
    return DecisionRequest.ChooseColor(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        // CR 113.7c: the resolving object as last known — an ability's source may already be gone.
        cardObjectId = entry.resolutionSourceId,
        card = entry.resolutionSourceCard,
        options = Color.entries.toList(),
    )
}

/**
 * Applies the chosen colour of a chosen-colour clause (CR 609.4): clears the pause, runs whatever the
 * resolving object's [ChosenColorEffect] says to do with [color], and then completes the resolution
 * through [completeClauseResolution] — the shared completion that knows a spell goes to a graveyard
 * (CR 608.2m, or to exile for a flashback cast, CR 702.34e) and an ability merely ceases to exist
 * (CR 113.7a).
 *
 * The `when` is exhaustive over [ChosenColorEffect], so a future member cannot default into creating a
 * prevention shield.
 */
internal fun applyChosenColorClause(
    state: GameState,
    color: Color,
): AdvanceResult {
    state.pendingChosenColor ?: error("no chosen-colour clause is pending")
    val entry = resolvingClauseEntry(state)
    val clause =
        entry.resolutionClauses.chosenColorEffect
            ?: error("CR 609.4: ${entry.resolutionSourceCard.name} has no chosen-colour clause to apply")
    val cleared = state.copy(pendingChosenColor = null)
    val applied =
        when (clause) {
            ChosenColorEffect.PreventDamageFromChosenColorThisTurn ->
                preventDamageFromColorThisTurn(
                    cleared,
                    color,
                    entry.resolutionSourceCard,
                    entry.resolutionSourceId,
                )
        }
    return completeClauseResolution(applied, entry)
}
