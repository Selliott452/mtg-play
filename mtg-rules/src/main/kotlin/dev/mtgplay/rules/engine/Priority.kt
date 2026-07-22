package dev.mtgplay.rules.engine

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.AdvanceResult

/**
 * Opens a fresh priority round: clears every player's round standing and gives the active
 * player priority (CR 117.3b — the active player receives priority at the beginning of most
 * steps and phases, after turn-based actions).
 */
internal fun grantPriorityRound(state: GameState): AdvanceResult {
    val cleared = clearPriorityRound(state)
    return priorityTo(cleared, cleared.turn.activePlayer)
}

/** Resets every player's [PriorityStatus] to [PriorityStatus.NONE] — no round is open. */
internal fun clearPriorityRound(state: GameState): GameState =
    state.players.keys.fold(state) { current, seat ->
        current.updatePlayer(seat) { it.copy(priorityStatus = PriorityStatus.NONE) }
    }

/**
 * Gives [seat] priority and suspends with their priority window. State-based actions are
 * checked first — CR 704.3: whenever a player *would* receive priority — and a loss there ends
 * the game instead of opening the window.
 */
internal fun priorityTo(
    state: GameState,
    seat: PlayerId,
): AdvanceResult =
    when (val outcome = performStateBasedActions(state)) {
        is SbaOutcome.Loss -> AdvanceResult.GameOver(outcome.state, outcome.result)
        is SbaOutcome.Continued -> {
            val paused =
                outcome.state.updatePlayer(seat) { it.copy(priorityStatus = PriorityStatus.HOLDS_PRIORITY) }
            AdvanceResult.NeedsDecision(paused, chooseActionRequest(paused, seat))
        }
    }

/**
 * Applies [seat]'s pass of priority (CR 117.3d): the next player in turn order receives
 * priority; when all players have passed in succession the round ends (CR 117.4).
 */
internal fun applyPassPriority(
    state: GameState,
    seat: PlayerId,
): AdvanceResult {
    val passed =
        state
            .updatePlayer(seat) { it.copy(priorityStatus = PriorityStatus.HAS_PASSED) }
            .emit(GameEvent.PriorityPassed(seat))
    val allPassed = passed.players.values.all { it.priorityStatus == PriorityStatus.HAS_PASSED }
    return if (allPassed) {
        endOfPriorityRound(clearPriorityRound(passed))
    } else {
        priorityTo(passed, passed.seatAfter(seat))
    }
}

/**
 * All players passed in succession (CR 117.4). With a nonempty stack the topmost object
 * resolves (CR 608.1). With an empty stack the step or phase ends (CR 500.2); a completed
 * round *during cleanup* instead begins another cleanup step (CR 514.3a) — the current one
 * still ends, so mana pools empty (CR 500.4).
 */
internal fun endOfPriorityRound(state: GameState): AdvanceResult {
    if (state.sharedZones.stack.isNotEmpty()) {
        return resolveTopOfStack(state)
    }
    return when {
        state.turn.step == TurnStep.CLEANUP -> beginPosition(emptyManaPoolsAtPositionEnd(state))
        // CR 510.5: after the first-strike combat-damage step, the phase gets a second
        // combat-damage step instead of proceeding — re-enter the same position (the current
        // step still ends, so mana pools empty, CR 500.4).
        state.turn.step == TurnStep.COMBAT_DAMAGE && needsSecondCombatDamageStep(state) ->
            beginPosition(emptyManaPoolsAtPositionEnd(state))
        else -> advancePastCurrentPosition(state)
    }
}
