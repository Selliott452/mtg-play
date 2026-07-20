package dev.mtgplay.rules

import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.DecisionRequest

/**
 * What one engine advance produced (ADR-004): the engine ran autonomously as far as the rules
 * allow and either suspended for a player decision or finished the game.
 *
 * There is no third case — every interaction with a player flows through [NeedsDecision]; the
 * engine never calls back into a driver and never blocks.
 */
sealed interface AdvanceResult {
    /**
     * The engine is suspended: [request] must be answered (via `GameEngine.advance`) before the
     * game can proceed. [state] is the paused state the answer applies to.
     *
     * @property state the immutable game state at the pause.
     * @property request who decides, and the enumerated options to decide among (ADR-005).
     */
    data class NeedsDecision(
        val state: GameState,
        val request: DecisionRequest,
    ) : AdvanceResult

    /**
     * The game is over (CR 104.1). No further advance is legal on [state].
     *
     * @property state the final game state, including the closing events.
     * @property result who won, who lost, and why.
     */
    data class GameOver(
        val state: GameState,
        val result: MatchResult,
    ) : AdvanceResult
}
