package dev.mtgplay.server

import dev.mtgplay.core.identity.PlayerId
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close

/**
 * The [SeatLink] implementation over a Ktor [WebSocketServerSession] (ADR-008): the only place the
 * match model touches the transport. [Match] hands it encoded strings; this forwards them as text
 * frames and closes the socket on supersede.
 *
 * @property seat the seat this connection serves.
 * @property session the live WebSocket session; owned by one connection handler.
 */
internal class KtorSeatLink(
    override val seat: PlayerId,
    private val session: WebSocketServerSession,
) : SeatLink {
    override suspend fun send(frame: String) {
        session.send(Frame.Text(frame))
    }

    override suspend fun supersede() {
        session.close(CloseReason(CLOSE_SUPERSEDED, "seat reconnected on a newer connection"))
    }
}
