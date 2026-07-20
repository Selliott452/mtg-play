package dev.mtgplay.rules

import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.Decision

/**
 * The resumable rules engine (ADR-004): a state machine that advances the game autonomously
 * until it needs a player decision, then suspends and returns a typed request.
 *
 * Both operations are **pure**: they never mutate their inputs and are deterministic — equal
 * inputs produce equal results, which is what makes `(MatchConfig, List<Decision>)` a complete
 * replay record (ADR-006). Implementations hold no per-game state of their own; everything
 * lives in the [GameState] that flows through.
 */
interface GameEngine {
    /**
     * Starts a new game from [config]: determines the starting player (CR 103.1), shuffles each
     * library from the match PRNG (CR 103.1, ADR-006), draws opening hands (CR 103.5; mulligans
     * deferred to Phase 6), and advances into turn 1 until the first decision is needed.
     *
     * Normally returns [AdvanceResult.NeedsDecision]; a degenerate configuration (e.g. an empty
     * deck) can end the game before any decision, returning [AdvanceResult.GameOver].
     */
    fun start(config: MatchConfig): AdvanceResult

    /**
     * Applies [decision] to the paused [state] and advances until the next decision is needed
     * or the game ends.
     *
     * [state] must be a state the engine previously returned inside an
     * [AdvanceResult.NeedsDecision], and [decision] must answer exactly the pending request:
     * a decision naming any other request, an out-of-range index, a wrong-arity or duplicated
     * multi-select, or a decision shape that does not match the pending request kind all fail
     * loudly with [IllegalArgumentException] — never a silent approximation (ADR-004; replay
     * integrity, ADR-006). Advancing a state that is not paused at a decision point fails with
     * [IllegalStateException].
     */
    fun advance(
        state: GameState,
        decision: Decision,
    ): AdvanceResult
}
