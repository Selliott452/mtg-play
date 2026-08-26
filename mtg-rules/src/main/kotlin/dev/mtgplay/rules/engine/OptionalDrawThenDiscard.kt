package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.DiscardExemption
import dev.mtgplay.core.definition.OptionalDrawThenDiscard
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingOptionalDraw
import dev.mtgplay.core.state.PendingResolutionDiscard
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.resolutionClauses
import dev.mtgplay.core.state.resolutionController
import dev.mtgplay.core.state.resolutionSourceCard
import dev.mtgplay.core.state.resolutionSourceId
import dev.mtgplay.rules.AdvanceResult

/*
 * The "you may draw N. If you do, discard M unless <condition>" clause (CR 601.3b, CR 701.8) —
 * Moon-Circuit Hacker's combat-damage trigger. Additive (`W9-A`), a member of the `FW-CLAUSEHOOK` family
 * (docs/design/resolution-clause-hook.md) and the first with **two** pauses.
 *
 * The chain, and why each link is where it is:
 *
 * 1. **The yes/no** is the ordinary optional-draw pause. It reuses [PendingOptionalDraw] and the
 *    `ChooseYesNo` request it derives rather than minting a record and a `DecisionRequest` member of its
 *    own: the question a seat is being asked ("draw a card off this source?") is the same question with
 *    the same options, and a second request member would cost five exhaustive dispatch sites plus the
 *    protocol/CLI round for no observable difference. What *differs* is what happens after the answer,
 *    and that is read from the resolving object's own clause rather than from the pending record — which
 *    is exactly the ADR-004 rule that a decision re-derives from state.
 * 2. **The tail runs only on "yes"**, because "**if you do**" is a real conditional. Declining ends the
 *    resolution with nothing discarded.
 * 3. **The "unless" is checked after the draw**, against the trigger's last-known information rather
 *    than the live battlefield (CR 603.10) — see [discardIsExempt].
 * 4. **The discard itself** is the ordinary mandatory-selection pause ([PendingResolutionDiscard]), so
 *    it routes through the CR 614/616 discard framework and a discarded madness card is exiled instead.
 *
 * Nothing here is a second implementation of either half: both pauses, both requests and both apply
 * functions are the existing ones, and this file is the chaining between them.
 */

/**
 * Runs the "you may draw, then maybe discard" clause of the resolving [entry] (CR 601.3b): pauses for
 * its controller's yes/no. Called by the clause hook after the object's ordinary effect, while it is
 * still on top of the stack.
 *
 * The pause record is [PendingOptionalDraw] — the same record the bare optional draw opens — so
 * [applyOptionalDrawYesNo] is the single place the answer lands, and [afterOptionalDraw] is where the two
 * clauses part company.
 */
internal fun orchestrateOptionalDrawThenDiscard(
    state: GameState,
    entry: StackEntry,
    clause: OptionalDrawThenDiscard,
): AdvanceResult {
    val paused =
        state.copy(
            pendingOptionalDraw =
                PendingOptionalDraw(
                    decider = entry.resolutionController,
                    drawCount = clause.drawCount,
                    // CR 113.7c: the source as last known — an ability's source may already be gone.
                    sourceId = entry.resolutionSourceId,
                    sourceCard = entry.resolutionSourceCard,
                ),
        )
    return AdvanceResult.NeedsDecision(paused, pendingOptionalDrawRequest(paused))
}

/**
 * What happens once an optional draw's yes/no has been answered and any cards drawn (CR 601.3b): the
 * conditional discard of an [OptionalDrawThenDiscard], or the end of the resolution.
 *
 * The fork is read from the **resolving object's declared clause**, not from the pending record: at most
 * one clause may be declared ([dev.mtgplay.core.definition.requireAtMostOneClause]), so the two cannot be
 * confused, and re-deriving it from the top of the stack keeps the resume path a pure function of the
 * state (ADR-004).
 *
 * Three ways the tail does not happen, and all three are the printed line rather than special cases:
 * the draw was declined ("**if you do**"), the "unless" holds ([discardIsExempt]), or the hand is empty
 * after the draw — there is nothing to discard, so the object simply leaves the stack.
 */
internal fun afterOptionalDraw(
    state: GameState,
    entry: StackEntry,
    decider: PlayerId,
    accepted: Boolean,
): AdvanceResult {
    val clause = entry.resolutionClauses.optionalDrawThenDiscard
    if (clause == null || !accepted || discardIsExempt(state, entry, clause)) {
        return completeClauseResolution(state, entry)
    }
    val count = minOf(clause.discardCount, state.player(decider).hand.size)
    if (count == 0) return completeClauseResolution(state, entry)
    val paused = state.copy(pendingResolutionDiscard = PendingResolutionDiscard(decider, count))
    return AdvanceResult.NeedsDecision(paused, pendingResolutionDiscardRequest(paused))
}

/**
 * Whether the clause's printed "unless" cancels its discard (CR 701.8).
 *
 * [DiscardExemption.SOURCE_ENTERED_THIS_TURN] — "unless **this creature** entered this turn" — is a
 * question about the ability's own source permanent, so it is answered from the trigger's last-known
 * information ([dev.mtgplay.core.state.PendingTrigger.sourceEnteredTurn], CR 603.10) and **not** from a
 * live battlefield lookup: the source may have been killed in response to the very trigger asking, and a
 * lookup that found nothing would silently answer "did not enter this turn" and force a discard the card
 * never asked for.
 *
 * It therefore only means anything on a **triggered** ability, and a definition that declares it anywhere
 * else fails loudly rather than quietly answering no — a spell has no source permanent that could have
 * entered, and an activated ability's source would need its own capture.
 */
private fun discardIsExempt(
    state: GameState,
    entry: StackEntry,
    clause: OptionalDrawThenDiscard,
): Boolean =
    when (clause.skipDiscardWhen) {
        DiscardExemption.NEVER -> false
        DiscardExemption.SOURCE_ENTERED_THIS_TURN -> {
            val trigger =
                (entry as? StackEntry.Ability)?.trigger
                    ?: error(
                        "CR 603.10: '${clause.skipDiscardWhen}' asks whether this ability's source " +
                            "permanent entered this turn, which only a triggered ability captures; " +
                            "${entry.resolutionSourceCard.name} declares it on a $entry",
                    )
            trigger.sourceEnteredTurn == state.turn.number
        }
    }
