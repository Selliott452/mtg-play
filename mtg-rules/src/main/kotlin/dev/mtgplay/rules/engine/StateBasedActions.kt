package dev.mtgplay.rules.engine

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.event.LossReason
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.MatchResult

/**
 * One applicable state-based action (CR 704.5): something the game itself does the moment its
 * condition holds, checked whenever a player would receive priority (CR 704.3).
 *
 * Sealed so the performer handles every kind exhaustively; Phase 3+ adds members (lethal-damage
 * destruction 704.5g, zero-toughness 704.5f, aura legality 704.5m/n, …) next to the checks that
 * detect them, without reshaping the check-and-repeat loop.
 */
internal sealed interface StateBasedAction {
    /**
     * [player] loses the game for [reason]: life 0 or less (CR 704.5a) or an attempted draw
     * from an empty library since the last check (CR 704.5c).
     */
    data class PlayerLoses(
        val player: PlayerId,
        val reason: LossReason,
    ) : StateBasedAction
}

/**
 * All state-based actions applicable to [state] right now (CR 704.5), in deterministic seat
 * order. The P1.2 checks are the two player-loss conditions; later phases append checks here.
 */
internal fun applicableStateBasedActions(state: GameState): List<StateBasedAction> =
    buildList {
        for ((seat, playerState) in state.players) {
            if (playerState.life <= 0) {
                // CR 704.5a: a player with 0 or less life loses the game.
                add(StateBasedAction.PlayerLoses(seat, LossReason.LIFE_TOTAL_ZERO_OR_LESS))
            }
            if (playerState.attemptedDrawFromEmptyLibrary) {
                // CR 704.5c: a player who attempted to draw from an empty library since the
                // last check loses the game. Acts on the recorded attempt, not on emptiness.
                add(StateBasedAction.PlayerLoses(seat, LossReason.ATTEMPTED_DRAW_FROM_EMPTY_LIBRARY))
            }
        }
    }

/** The result of one full state-based-action check (the CR 704.3 repeat-until-quiet loop). */
internal sealed interface SbaOutcome {
    /**
     * No further state-based actions apply; play proceeds. [performedWork] records whether any
     * check in the loop performed anything — the fact CR 514.3a's cleanup rule branches on.
     */
    data class Continued(
        val state: GameState,
        val performedWork: Boolean,
    ) : SbaOutcome

    /** A player lost; the game is over (CR 104.2a). [state] already carries the closing events. */
    data class Loss(
        val state: GameState,
        val result: MatchResult,
    ) : SbaOutcome
}

/**
 * Performs state-based actions until none apply (CR 704.3): each iteration collects every
 * applicable action, performs the batch simultaneously as a single event (CR 704.3), and
 * checks again. A player loss ends the loop — and the game — immediately; any other batch
 * (none exist until Phase 3) feeds the loop's next iteration.
 */
internal fun performStateBasedActions(state: GameState): SbaOutcome {
    var current = SbaOutcome.Continued(state, performedWork = false)
    while (true) {
        val actions = applicableStateBasedActions(current.state)
        if (actions.isEmpty()) return current
        when (val batch = performBatch(current.state, actions)) {
            is SbaOutcome.Loss -> return batch
            is SbaOutcome.Continued -> current = SbaOutcome.Continued(batch.state, performedWork = true)
        }
    }
}

// Performs one batch of applicable state-based actions simultaneously (CR 704.3).
private fun performBatch(
    state: GameState,
    actions: List<StateBasedAction>,
): SbaOutcome {
    val losses = mutableListOf<StateBasedAction.PlayerLoses>()
    for (action in actions) {
        when (action) {
            is StateBasedAction.PlayerLoses -> losses += action
        }
    }
    val losers = losses.distinctBy(StateBasedAction.PlayerLoses::player)
    if (losers.size > 1) {
        error(
            "CR 104.4a: players ${losers.map(StateBasedAction.PlayerLoses::player)} would lose " +
                "simultaneously; draws are not supported in P1.2",
        )
    }
    val loss =
        losers.firstOrNull()
            ?: error("unreachable in P1.2: every state-based action is a player loss, got $actions")
    return loseGame(state, loss.player, loss.reason)
}

/**
 * Ends the game with [loser] losing for [reason]: in a two-player game the losing player's only
 * opponent wins (CR 104.2a). Emits [GameEvent.PlayerLost] and [GameEvent.GameEnded].
 */
internal fun loseGame(
    state: GameState,
    loser: PlayerId,
    reason: LossReason,
): SbaOutcome.Loss {
    val winner =
        state.players.keys.singleOrNull { it != loser }
            ?: error("CR 104.2a: exactly one opponent must remain; games with more seats are unsupported in P1.2")
    val final =
        state
            .emit(GameEvent.PlayerLost(loser, reason))
            .emit(GameEvent.GameEnded(winner, loser))
    return SbaOutcome.Loss(final, MatchResult(winner, loser, reason))
}
