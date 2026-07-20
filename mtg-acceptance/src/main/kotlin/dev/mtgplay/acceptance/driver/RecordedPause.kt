package dev.mtgplay.acceptance.driver

import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.DecisionRequest

/**
 * One engine suspension observed while driving a game: the paused [state] and the [request] the
 * engine surfaced against it.
 *
 * The scripted driver accumulates a pause per suspension, giving acceptance suites the sequence of
 * `(state, request)` pairs a game passed through — enough to assert phase/step order, priority
 * alternation, and discard timing without re-running the engine.
 *
 * @property state the immutable game state at the pause.
 * @property request the decision the engine is waiting on.
 */
data class RecordedPause(
    val state: GameState,
    val request: DecisionRequest,
)
