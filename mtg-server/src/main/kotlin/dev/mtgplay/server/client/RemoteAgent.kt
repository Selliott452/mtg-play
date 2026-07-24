package dev.mtgplay.server.client

import dev.mtgplay.protocol.DecisionDto
import dev.mtgplay.protocol.DecisionRequestDto

/**
 * A pluggable decision policy for a [ReferenceClient] (ADR-008): given the enumerated wire request the
 * seat must answer, it returns the chosen [DecisionDto]. A functional interface, so an agent can be a
 * lambda, a class ([RandomRemoteAgent]), or a bridge to an out-of-process trainer.
 *
 * **Schema-only by contract.** An agent sees only `mtg-protocol` DTOs — never the engine — so it is
 * bound to the wire schema, not to `mtg-rules`. Every option in a [DecisionRequestDto] is
 * engine-enumerated (ADR-005), so a policy chooses by index and never constructs an action freeform.
 * This is the ADR-008 payoff: an agent author needs the schema module alone to play a whole game.
 */
fun interface RemoteAgent {
    /** Chooses a legal [DecisionDto] for [request] (ADR-005: pick one of the enumerated options by index). */
    fun decide(request: DecisionRequestDto): DecisionDto
}
