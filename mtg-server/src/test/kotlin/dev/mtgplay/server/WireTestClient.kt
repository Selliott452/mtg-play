package dev.mtgplay.server

import dev.mtgplay.protocol.ClientMessage
import dev.mtgplay.protocol.DecisionDto
import dev.mtgplay.protocol.DecisionRequestDto
import dev.mtgplay.protocol.DecisionViewDto
import dev.mtgplay.protocol.MatchResultDto
import dev.mtgplay.protocol.PROTOCOL_VERSION
import dev.mtgplay.protocol.ServerMessage
import dev.mtgplay.protocol.decodeServerMessage
import dev.mtgplay.protocol.encode
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText

/*
 * The in-test wire client (ADR-008, P7.2): a schema-only driver over a Ktor client WebSocket session.
 * It speaks only `mtg-protocol` DTOs and the codec — never the engine — proving a client needs
 * nothing but the schema to play. Reused by the full-game, reconnect, and rejection suites.
 */

/** Guard so a stuck game fails loudly rather than hanging; far above any real playout's decision count. */
private const val DEFAULT_MAX_DECISIONS: Int = 200_000

/**
 * What one seat's wire play produced.
 *
 * @property result the [MatchResultDto] from the closing `GameOver` envelope.
 * @property decisionsSent how many decisions this seat answered (its share of the final-request count).
 * @property envelopes how many server envelopes this seat received.
 * @property allEnvelopesVersioned whether every received envelope carried [PROTOCOL_VERSION].
 */
data class SeatRun(
    val result: MatchResultDto,
    val decisionsSent: Int,
    val envelopes: Int,
    val allEnvelopesVersioned: Boolean,
)

/** Sends the connect handshake: the seat token as the first text frame. */
suspend fun DefaultClientWebSocketSession.sendToken(token: SeatToken) {
    send(Frame.Text(token.value))
}

/** Blocks until the next text frame and returns its payload (skipping any non-text control frames). */
suspend fun DefaultClientWebSocketSession.nextText(): String {
    while (true) {
        val frame = incoming.receive()
        if (frame is Frame.Text) return frame.readText()
    }
}

/** Encodes and sends a decision, optionally under a non-current [version] (for the version-skew test). */
suspend fun DefaultClientWebSocketSession.sendDecision(
    decision: DecisionDto,
    version: String = PROTOCOL_VERSION,
) {
    send(Frame.Text(ClientMessage.DecisionMessage(version, decision).encode()))
}

/**
 * Plays this seat with [chooser] until the game ends, answering every `SeatUpdate` whose pending
 * decision is [DecisionViewDto.ToDecide] and ignoring frames where another seat decides (the resync
 * of ADR-007 makes waiting safe — the next relevant frame will arrive). Returns the [SeatRun].
 */
suspend fun DefaultClientWebSocketSession.playToGameOver(
    chooser: SchemaRandomChooser,
    maxDecisions: Int = DEFAULT_MAX_DECISIONS,
): SeatRun {
    var decisions = 0
    var envelopes = 0
    var allVersioned = true
    while (true) {
        val message = decodeServerMessage(nextText())
        envelopes++
        if (message.protocolVersion != PROTOCOL_VERSION) allVersioned = false
        when (message) {
            is ServerMessage.SeatUpdate -> {
                val pending = message.view.pendingDecision
                if (pending is DecisionViewDto.ToDecide) {
                    check(decisions < maxDecisions) {
                        "seat answered $maxDecisions decisions without a GameOver — game did not terminate"
                    }
                    sendDecision(chooser.choose(pending.request))
                    decisions++
                }
            }
            is ServerMessage.GameOver -> return SeatRun(message.result, decisions, envelopes, allVersioned)
        }
    }
}

/**
 * Receives frames until this seat is the one to decide, and returns that pending request without
 * answering it — the setup for the reconnection test (drop while a request is outstanding).
 */
suspend fun DefaultClientWebSocketSession.awaitToDecide(): DecisionRequestDto {
    while (true) {
        val message = decodeServerMessage(nextText())
        if (message is ServerMessage.SeatUpdate) {
            val pending = message.view.pendingDecision
            if (pending is DecisionViewDto.ToDecide) return pending.request
        }
    }
}
