package dev.mtgplay.rules

import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.engine.applyDecision
import dev.mtgplay.rules.engine.startGame

/**
 * The standard [GameEngine] (ADR-004): CR 500s turn structure, CR 117 priority, the CR 704
 * state-based-action loop, and game over — the P1.2 skeleton the later packets grow (the stack
 * and casting in P2.1, combat actions in Phase 3, and so on).
 *
 * Stateless and pure: both operations are functions of their arguments alone, so one instance
 * can serve any number of games concurrently, and equal inputs always produce equal results
 * (ADR-006).
 *
 * Internal shape, for extenders: the engine is a chain of small transition functions
 * (`beginTurn` -> `beginPosition` -> turn-based actions -> priority rounds), each returning
 * [AdvanceResult]. A pause is encoded entirely in the returned state — who holds priority, or
 * that a cleanup discard is due — so `advance` re-derives the pending request from the state,
 * validates the decision against it, applies it, and re-enters the same chain.
 */
class DefaultGameEngine : GameEngine {
    override fun start(config: MatchConfig): AdvanceResult = startGame(config)

    override fun advance(
        state: GameState,
        decision: Decision,
    ): AdvanceResult = applyDecision(state, decision)
}
