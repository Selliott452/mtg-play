package dev.mtgplay.acceptance.driver

import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest

/**
 * A policy for answering the engine's decision requests: given the pending [DecisionRequest] and
 * the paused [GameState] it applies to, produce the [Decision] that answers it (ADR-004, ADR-005).
 *
 * Modelling the answer as a function of the request (and, when a policy needs it, the state) is
 * what lets later phases' richer requests slot in unchanged: a responder `when`s over the
 * [DecisionRequest] hierarchy, so a new request kind breaks its compilation rather than falling
 * through silently. The scripted driver, the pass-everything convenience, and the random-legal
 * driver are all just responders.
 */
fun interface Responder {
    /**
     * Answers [request], which the engine surfaced against [state]. The returned decision must
     * name exactly this request and select in-range, correctly-sized indices — otherwise the
     * engine rejects it loudly (ADR-004).
     */
    fun respond(
        request: DecisionRequest,
        state: GameState,
    ): Decision
}
