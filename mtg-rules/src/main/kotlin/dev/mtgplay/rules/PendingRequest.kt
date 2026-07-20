package dev.mtgplay.rules

import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.pendingDecisionRequest

/**
 * The decision request [state] is paused at, or `null` if the state is not a pause point.
 *
 * A pause is encoded entirely in the state (ADR-004), so the pending request is a **pure
 * derivation** — this is the same computation `GameEngine.advance` validates decisions
 * against, exposed for drivers that hold a paused state without the [AdvanceResult] that
 * produced it: harnesses resuming a stored state (the P2.1 acceptance driver), and later the
 * per-seat views of ADR-007/Phase 7.
 */
fun pendingRequestOf(state: GameState): DecisionRequest? = pendingDecisionRequest(state)
