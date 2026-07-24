package dev.mtgplay.server

import dev.mtgplay.protocol.PROTOCOL_VERSION
import dev.mtgplay.protocol.ServerMessage
import dev.mtgplay.protocol.decodeServerMessage
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.engine.embeddedServer
import io.ktor.websocket.Frame
import java.io.IOException
import java.net.ServerSocket
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.server.cio.CIO as ServerCIO

private const val SMOKE_SEED: Long = 0x5150
private const val LOOPBACK: String = "127.0.0.1"
private const val STOP_GRACE_MS: Long = 100
private const val STOP_TIMEOUT_MS: Long = 500

/**
 * The real-socket smoke (ADR-008): unlike the in-process `testApplication` suites, this binds a real
 * CIO engine to an ephemeral localhost port and drives it with a real CIO client — end-to-end over an
 * actual socket. If the sandbox forbids binding, the test **skips gracefully with a flag** rather than
 * failing (the hard constraint: `testApplication` is the primary vehicle; a socket bind is best-effort).
 */
class RealSocketSmokeSpec :
    StringSpec({
        "ADR-008: a real CIO localhost socket serves a versioned SeatUpdate (skipped-with-flag if bind forbidden)" {
            if (!canBindLocalhost()) {
                println("[P7.2 real-socket smoke] SKIPPED: the sandbox forbids binding a localhost socket")
            } else {
                val server = MatchServer()
                val handle = server.createMatch(MvpMatch.config(SMOKE_SEED))
                val embedded = embeddedServer(ServerCIO, host = LOOPBACK, port = 0) { matchModule(server) }
                embedded.start(wait = false)
                try {
                    val connectors = embedded.engine.resolvedConnectors()
                    val port = connectors.first().port
                    val client = HttpClient(ClientCIO) { install(ClientWebSockets) }
                    client.use { http ->
                        http.webSocket(host = LOOPBACK, port = port, path = "/matches/${handle.id.value}") {
                            send(Frame.Text(handle.tokens.getValue(MvpMatch.monoRedSeat).value))
                            val message = decodeServerMessage(nextText())
                            message.protocolVersion shouldBe PROTOCOL_VERSION
                            message.shouldBeInstanceOf<ServerMessage.SeatUpdate>()
                        }
                    }
                } finally {
                    embedded.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS)
                }
            }
        }
    })

/** Probes whether the sandbox permits binding a localhost server socket at all (the smoke's gate). */
private fun canBindLocalhost(): Boolean =
    try {
        ServerSocket(0).use { true }
    } catch (failure: IOException) {
        println("[P7.2 real-socket smoke] localhost bind probe failed: ${failure.message}")
        false
    }
