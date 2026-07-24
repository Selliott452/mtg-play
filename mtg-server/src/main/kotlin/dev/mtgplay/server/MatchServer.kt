package dev.mtgplay.server

import dev.mtgplay.rules.MatchConfig
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText

/**
 * The reference WebSocket host (ADR-008): a thin driver that owns a [MatchRegistry], accepts a seat
 * connection, authenticates it against a match, and pumps frames between the socket and the [Match].
 * It is deliberately small — executable documentation of the `mtg-protocol` schema, not an
 * operational server (no matchmaking, persistence, TLS, or real auth; those are consumer territory).
 *
 * One route: `GET /matches/{matchId}` upgraded to WebSocket. The connect handshake is a single
 * leading text frame carrying the seat token (kept out of the URL so a token never lands in a log or
 * query string). The server resolves the token to a seat, [Match.attach]es (which resyncs the seat),
 * then forwards every subsequent text frame to [Match.submit].
 *
 * @property registry the match store; a fresh in-memory one by default.
 */
class MatchServer(
    val registry: MatchRegistry = MatchRegistry(),
) {
    /** Starts and registers a new match from [config]; returns its handle (id, seed, seat tokens). */
    fun createMatch(config: MatchConfig): MatchHandle = registry.create(config)

    /**
     * Drives one seat connection: resolve the match, authenticate the leading token frame, attach
     * (resync), then pump decisions until the socket closes. All rejection paths reply loudly (a
     * fatal one closes with a 4xxx code; a recoverable one is a [Match]-level [ServerError]) and none
     * corrupts the match. The `finally` always detaches so a drop frees the seat for reconnection.
     */
    suspend fun serve(session: WebSocketServerSession) {
        val match = session.call.parameters["matchId"]?.let { registry.find(MatchId(it)) }
        if (match == null) {
            session.reject(ServerErrorCode.MATCH_NOT_FOUND, "no match for that id", CLOSE_MATCH_NOT_FOUND)
            return
        }
        val tokenText = session.firstTextFrame()
        val seat = tokenText?.trim()?.let { match.seatFor(SeatToken(it)) }
        if (seat == null) {
            session.reject(ServerErrorCode.BAD_TOKEN, "unrecognized seat token", CLOSE_BAD_TOKEN)
            return
        }
        val link = KtorSeatLink(seat, session)
        match.attach(link)
        try {
            for (frame in session.incoming) {
                if (frame is Frame.Text) match.submit(seat, frame.readText())
            }
        } finally {
            match.detach(link)
        }
    }

    private suspend fun WebSocketServerSession.firstTextFrame(): String? {
        for (frame in incoming) {
            if (frame is Frame.Text) return frame.readText()
        }
        return null
    }

    private suspend fun WebSocketServerSession.reject(
        code: ServerErrorCode,
        detail: String,
        closeCode: Short,
    ) {
        send(Frame.Text(ServerError(code, detail).encode()))
        close(CloseReason(closeCode, detail))
    }
}

/**
 * Installs the [server]'s single WebSocket route on this [Application] (ADR-008). Callers wire this
 * into an `embeddedServer { }` (see the server main) or a `testApplication { application { } }`.
 */
fun Application.matchModule(server: MatchServer) {
    install(WebSockets)
    routing {
        webSocket("/matches/{matchId}") {
            server.serve(this)
        }
    }
}
