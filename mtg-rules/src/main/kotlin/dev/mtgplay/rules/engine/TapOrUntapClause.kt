package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.OptionalTapOrUntap
import dev.mtgplay.core.definition.TapOrUntapChoice
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingTapOrUntap
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.resolutionController
import dev.mtgplay.core.state.resolutionSourceCard
import dev.mtgplay.core.state.resolutionSourceId
import dev.mtgplay.core.state.resolutionTargets
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.effect.tapPermanent
import dev.mtgplay.rules.effect.untapPermanent

/*
 * The "you may tap **or** untap [target]" clause (CR 701.20a, CR 701.21a) — Sewer-veillance Cam.
 * Additive (`W8-G`), a member of the `FW-CLAUSEHOOK` family (docs/design/resolution-clause-hook.md).
 *
 * **It is here rather than in `SpellModes.kt` because the card is not modal**, and that correction is
 * what unblocked the card: `FW-TAPUNTAP` dropped Sewer-veillance Cam recording that its resolution was
 * "a *mode* choice on a triggered ability" and that modal resolution existed only for spells. CR 700.2
 * makes an object modal only when it prints two or more options in a bulleted list preceded by an
 * instruction to choose among them; "you may tap or untap target creature" prints neither, and the
 * choice happens at CR 608.2c as the ability resolves rather than at CR 601.2b as it goes on the stack.
 * [OptionalTapOrUntap] carries the full argument and the observable difference.
 *
 * The clause therefore needed no modal machinery at all — it needed the shape every other
 * mid-resolution decision in this engine already has: a `pending*` record, a request re-derived from the
 * paused state (ADR-004), and an application that finishes through [completeClauseResolution].
 */

/**
 * Runs the tap-or-untap clause of the resolving [entry] (CR 608.2c): pauses for its controller's
 * three-way answer. Called by the clause hook after the object's ordinary effect, while it is still on
 * top of the stack.
 *
 * **An object that targeted nothing resolves straight through.** Sewer-veillance Cam's trigger is put on
 * the stack with no target when no creature is on the battlefield (CR 603.3d's vacuous case), and CR
 * 608.2c says an instruction with no object to carry it out on simply does nothing — so there is no
 * question to ask and no pause to open. Asking anyway would surface a decision whose every answer is a
 * no-op, which is noise in an agent's action space rather than a choice (ADR-005).
 */
internal fun orchestrateTapOrUntap(
    state: GameState,
    entry: StackEntry,
    @Suppress("UNUSED_PARAMETER") clause: OptionalTapOrUntap,
): AdvanceResult {
    val target = entry.resolutionTargets.singleOrNull()
    if (target == null) return completeClauseResolution(state, entry)
    check(target is Target.Permanent) {
        "CR 115.1b: a tap-or-untap clause acts on a targeted permanent, got $target"
    }
    val paused =
        state.copy(
            pendingTapOrUntap =
                PendingTapOrUntap(
                    decider = entry.resolutionController,
                    targetId = target.id,
                    // CR 113.7c: the source as last known — the Cam's leaves trigger has none on the board.
                    sourceId = entry.resolutionSourceId,
                    sourceCard = entry.resolutionSourceCard,
                ),
        )
    return AdvanceResult.NeedsDecision(paused, pendingTapOrUntapRequest(paused))
}

/**
 * The three-way answer the open tap-or-untap clause is waiting on (CR 608.2c). A pure function of the
 * state (ADR-004): the option list is the whole of [TapOrUntapChoice], which does not depend on the
 * board — see [DecisionRequest.ChooseTapOrUntap] for why the two no-op answers are still offered.
 */
internal fun pendingTapOrUntapRequest(state: GameState): DecisionRequest.ChooseTapOrUntap {
    val pending = state.pendingTapOrUntap ?: error("no tap-or-untap choice is pending")
    return DecisionRequest.ChooseTapOrUntap(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        cardObjectId = pending.sourceId,
        card = pending.sourceCard,
        targetId = pending.targetId,
        options = TapOrUntapChoice.entries.toList(),
    )
}

/**
 * Applies one tap-or-untap answer (CR 608.2c), then completes the resolution through the shared
 * [completeClauseResolution] — which knows a spell's card goes to a graveyard (CR 608.2m) and an ability
 * merely ceases to exist (CR 113.7a).
 *
 * The tap and the untap are the published CR 701.20a/701.21a primitives, so a target already in the
 * requested status is silently unaffected and no event is emitted for a status that did not change —
 * which is exactly why [TapOrUntapChoice.DECLINE] and a redundant tap produce the same board and are
 * still different answers.
 */
internal fun applyTapOrUntapChoice(
    state: GameState,
    choice: TapOrUntapChoice,
): AdvanceResult {
    val pending = state.pendingTapOrUntap ?: error("no tap-or-untap choice is pending")
    val entry = resolvingClauseEntry(state)
    val cleared = state.copy(pendingTapOrUntap = null)
    // CR 608.2b re-checked the target as this resolution began, so it is on the battlefield; the
    // primitives fail loudly on an id that is not, which is the ADR-005 contract they document.
    val acted =
        when (choice) {
            TapOrUntapChoice.DECLINE -> cleared
            TapOrUntapChoice.TAP -> tapPermanent(cleared, pending.targetId)
            TapOrUntapChoice.UNTAP -> untapPermanent(cleared, pending.targetId)
        }
    return completeClauseResolution(acted, entry)
}
