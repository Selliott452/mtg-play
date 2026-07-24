package dev.mtgplay.protocol

import dev.mtgplay.rules.MatchResult
import dev.mtgplay.rules.SeatView
import dev.mtgplay.rules.decision.Decision
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A message the server sends a seated client (ADR-008): either the seat's filtered view (which
 * embeds the seat's decision context, so "view + request" is one message), or game over.
 *
 * Every envelope carries [protocolVersion] ([PROTOCOL_VERSION]) so a peer rejects a schema skew
 * loudly. Sealed so a consumer handles both cases exhaustively.
 */
@Serializable
sealed interface ServerMessage {
    /** The protocol version this message was produced under ([PROTOCOL_VERSION]). */
    val protocolVersion: String

    /**
     * The seat's current filtered [view] (ADR-007). When the seat is the one to decide, its view's
     * `pendingDecision` is the full request; otherwise it is the kind-only context.
     */
    @Serializable
    @SerialName("seat_update")
    data class SeatUpdate(
        override val protocolVersion: String,
        val view: SeatViewDto,
    ) : ServerMessage

    /**
     * The game is over (CR 104.1): the [result], plus the seat's final filtered [view].
     */
    @Serializable
    @SerialName("game_over")
    data class GameOver(
        override val protocolVersion: String,
        val result: MatchResultDto,
        val view: SeatViewDto,
    ) : ServerMessage
}

/**
 * A message a client sends the server (ADR-008): the client's [decision] answering the request in
 * the last [ServerMessage.SeatUpdate]. Carries [protocolVersion] like every envelope.
 */
@Serializable
sealed interface ClientMessage {
    /** The protocol version this message was produced under ([PROTOCOL_VERSION]). */
    val protocolVersion: String

    /** The client's answer to the pending request (ADR-005). */
    @Serializable
    @SerialName("decision")
    data class DecisionMessage(
        override val protocolVersion: String,
        val decision: DecisionDto,
    ) : ClientMessage
}

/** A [ServerMessage.SeatUpdate] carrying [view] at the current [PROTOCOL_VERSION]. */
fun seatUpdateMessage(view: SeatView): ServerMessage.SeatUpdate =
    ServerMessage.SeatUpdate(PROTOCOL_VERSION, view.toDto())

/** A [ServerMessage.GameOver] carrying [result] and the final [view] at the current [PROTOCOL_VERSION]. */
fun gameOverMessage(
    result: MatchResult,
    view: SeatView,
): ServerMessage.GameOver = ServerMessage.GameOver(PROTOCOL_VERSION, result.toDto(), view.toDto())

/** A [ClientMessage.DecisionMessage] carrying [decision] at the current [PROTOCOL_VERSION]. */
fun decisionMessage(decision: Decision): ClientMessage.DecisionMessage =
    ClientMessage.DecisionMessage(PROTOCOL_VERSION, decision.toDto())
