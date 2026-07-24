package dev.mtgplay.server.client

import dev.mtgplay.server.SeatToken
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.close

/**
 * The reference match client (ADR-008 amendment: transport code lives in the server module): connects
 * one seat to a running reference server over a real WebSocket, hands each `SeatUpdate` to a pluggable
 * [RemoteAgent], sends its decisions, and returns the seat's [SeatRun] when the game ends.
 *
 * It is executable documentation of the wire contract. The whole decision path — [RandomRemoteAgent] and
 * the [WireSession] helpers — depends only on `mtg-protocol` (plus `mtg-core`'s `Rng`), never on
 * `mtg-rules`: a consumer proves it can play a full game from the schema alone. This class adds only the
 * transport (Ktor's CIO WebSocket client), which is why it, not the agent, lives beside the server.
 *
 * @property host the server host to dial.
 * @property port the server port to dial.
 * @property matchId the match id to join (its registry handle, carried in the connect route).
 * @property token this seat's bearer token, sent as the first frame (kept out of the URL, ADR-008).
 */
class ReferenceClient(
    private val host: String,
    private val port: Int,
    private val matchId: String,
    private val token: String,
) {
    /**
     * Connects, plays this seat to the game's end with [agent], and returns its [SeatRun]. Owns and
     * closes its own [HttpClient]. Surfaces a server rejection as a [RemoteError] (via [playToGameOver]).
     */
    suspend fun play(agent: RemoteAgent): SeatRun {
        val http = HttpClient(CIO) { install(WebSockets) }
        try {
            val session = http.webSocketSession(host = host, port = port, path = "/matches/$matchId")
            session.sendToken(SeatToken(token))
            val run = session.playToGameOver(agent)
            session.close()
            return run
        } finally {
            http.close()
        }
    }
}
