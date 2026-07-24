package dev.mtgplay.server

import dev.mtgplay.protocol.DecisionDto
import dev.mtgplay.protocol.DecisionRequestDto
import dev.mtgplay.protocol.DecisionRequestIdDto
import dev.mtgplay.protocol.DecisionViewDto
import dev.mtgplay.protocol.PROTOCOL_VERSION
import dev.mtgplay.protocol.ServerMessage
import dev.mtgplay.protocol.decodeServerMessage
import dev.mtgplay.server.client.RandomRemoteAgent
import dev.mtgplay.server.client.nextText
import dev.mtgplay.server.client.sendDecision
import dev.mtgplay.server.client.sendToken
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.close

private const val REJECTION_SEED: Long = 0x99
private const val CHOOSER_SEED: Long = 3
private const val STALE_ORDINAL_OFFSET: Int = 999

/**
 * Every rejection path (ADR-008): a bad token, an unknown match, a wrong-seat decision, a stale
 * request id, a version skew, and malformed JSON. Each is answered with a structured [ServerError]
 * (a fatal one also closes the socket; a recoverable one re-sends the seat's request), and in every
 * recoverable case the match is proven **unharmed** — the correct seat still advances it. The
 * engine's loud validation (ADR-004) is the backstop; the server catches and translates, never
 * crashes and never corrupts state.
 */
class RejectionSpec :
    StringSpec({
        "ADR-008: an unrecognized seat token is rejected with BAD_TOKEN and leaves the match playable" {
            val server = MatchServer()
            val handle = server.createMatch(MvpMatch.config(REJECTION_SEED))
            testApplication {
                application { matchModule(server) }
                val client = createClient { install(WebSockets) }
                val path = "/matches/${handle.id.value}"

                val rejected = client.webSocketSession(path)
                rejected.send(Frame.Text("not-a-real-token"))
                errorCodeOf(rejected.nextText()) shouldBe ServerErrorCode.BAD_TOKEN.name
                rejected.close()

                // The match is untouched: a real token still connects and receives a versioned view.
                val valid = client.webSocketSession(path)
                valid.sendToken(handle.tokens.getValue(MvpMatch.monoRedSeat))
                decodeServerMessage(valid.nextText()).protocolVersion shouldBe PROTOCOL_VERSION
                valid.close()
            }
        }

        "ADR-008: a connection to an unknown match id is rejected with MATCH_NOT_FOUND" {
            val server = MatchServer()
            testApplication {
                application { matchModule(server) }
                val client = createClient { install(WebSockets) }
                val orphan = client.webSocketSession("/matches/no-such-match")
                errorCodeOf(orphan.nextText()) shouldBe ServerErrorCode.MATCH_NOT_FOUND.name
                orphan.close()
            }
        }

        "ADR-008: a decision from the non-deciding seat is rejected with WRONG_SEAT, match unharmed" {
            val server = MatchServer()
            val handle = server.createMatch(MvpMatch.config(REJECTION_SEED))
            testApplication {
                application { matchModule(server) }
                val seated = connectBoth(handle)

                seated.other.sendDecision(DecisionDto.SingleSelect(DecisionRequestIdDto(seated.otherSeat, 0), 0))
                errorCodeOf(seated.other.nextText()) shouldBe ServerErrorCode.WRONG_SEAT.name
                // The wrong seat's view is re-sent (still "elsewhere"), and the real decider advances.
                decodeServerMessage(seated.other.nextText()).shouldBeInstanceOf<ServerMessage.SeatUpdate>()
                // The match is unharmed: the real decider answers its pending request and the game advances.
                seated.decider.sendDecision(seated.chooser.decide(seated.deciderRequest))
                decodeServerMessage(seated.decider.nextText()).protocolVersion shouldBe PROTOCOL_VERSION
                seated.close()
            }
        }

        "ADR-008: a decision naming a stale request id is rejected with STALE_REQUEST, match unharmed" {
            val server = MatchServer()
            val handle = server.createMatch(MvpMatch.config(REJECTION_SEED))
            testApplication {
                application { matchModule(server) }
                val seated = connectBoth(handle)

                val pendingId = seated.deciderRequest.id
                val staleId = DecisionRequestIdDto(pendingId.seat, pendingId.ordinal + STALE_ORDINAL_OFFSET)
                seated.decider.sendDecision(DecisionDto.SingleSelect(staleId, 0))
                errorCodeOf(seated.decider.nextText()) shouldBe ServerErrorCode.STALE_REQUEST.name
                // The pending request is re-sent unchanged; answering it now advances the game.
                val resent =
                    decodeServerMessage(seated.decider.nextText()).shouldBeInstanceOf<ServerMessage.SeatUpdate>()
                (resent.view.pendingDecision as DecisionViewDto.ToDecide).request.id shouldBe seated.deciderRequest.id
                // The match is unharmed: the real decider answers its pending request and the game advances.
                seated.decider.sendDecision(seated.chooser.decide(seated.deciderRequest))
                decodeServerMessage(seated.decider.nextText()).protocolVersion shouldBe PROTOCOL_VERSION
                seated.close()
            }
        }

        "ADR-008: a message under an unknown protocol version is rejected with UNSUPPORTED_VERSION, match unharmed" {
            val server = MatchServer()
            val handle = server.createMatch(MvpMatch.config(REJECTION_SEED))
            testApplication {
                application { matchModule(server) }
                val seated = connectBoth(handle)

                seated.decider.sendDecision(DecisionDto.SingleSelect(seated.deciderRequest.id, 0), version = "9.9.9")
                errorCodeOf(seated.decider.nextText()) shouldBe ServerErrorCode.UNSUPPORTED_VERSION.name
                decodeServerMessage(seated.decider.nextText()).shouldBeInstanceOf<ServerMessage.SeatUpdate>()
                // The match is unharmed: the real decider answers its pending request and the game advances.
                seated.decider.sendDecision(seated.chooser.decide(seated.deciderRequest))
                decodeServerMessage(seated.decider.nextText()).protocolVersion shouldBe PROTOCOL_VERSION
                seated.close()
            }
        }

        "ADR-008: a malformed frame is rejected with MALFORMED_MESSAGE, match unharmed" {
            val server = MatchServer()
            val handle = server.createMatch(MvpMatch.config(REJECTION_SEED))
            testApplication {
                application { matchModule(server) }
                val seated = connectBoth(handle)

                seated.decider.send(Frame.Text("{ this is not valid json"))
                errorCodeOf(seated.decider.nextText()) shouldBe ServerErrorCode.MALFORMED_MESSAGE.name
                decodeServerMessage(seated.decider.nextText()).shouldBeInstanceOf<ServerMessage.SeatUpdate>()

                // An unknown message type (strict codec, ignoreUnknownKeys=false) is malformed too.
                seated.decider.send(Frame.Text("""{"type":"nonsense","protocolVersion":"$PROTOCOL_VERSION"}"""))
                errorCodeOf(seated.decider.nextText()) shouldBe ServerErrorCode.MALFORMED_MESSAGE.name
                decodeServerMessage(seated.decider.nextText()).shouldBeInstanceOf<ServerMessage.SeatUpdate>()

                // The match is unharmed: the real decider answers its pending request and the game advances.
                seated.decider.sendDecision(seated.chooser.decide(seated.deciderRequest))
                decodeServerMessage(seated.decider.nextText()).protocolVersion shouldBe PROTOCOL_VERSION
                seated.close()
            }
        }
    })

/** Both connected seats, with the deciding one already identified from its first view. */
private class SeatedMatch(
    val decider: DefaultClientWebSocketSession,
    val deciderRequest: DecisionRequestDto,
    val other: DefaultClientWebSocketSession,
    val otherSeat: Int,
    val chooser: RandomRemoteAgent,
) {
    suspend fun close() {
        decider.close()
        other.close()
    }
}

/** Connects both seats, reads each first view, and packages the decider/other split for a rejection test. */
private suspend fun ApplicationTestBuilder.connectBoth(handle: MatchHandle): SeatedMatch {
    val client = createClient { install(WebSockets) }
    val path = "/matches/${handle.id.value}"
    val a = client.webSocketSession(path)
    val b = client.webSocketSession(path)
    a.sendToken(handle.tokens.getValue(MvpMatch.monoRedSeat))
    b.sendToken(handle.tokens.getValue(MvpMatch.boglesSeat))
    val viewA = decodeServerMessage(a.nextText()).shouldBeInstanceOf<ServerMessage.SeatUpdate>().view
    val viewB = decodeServerMessage(b.nextText()).shouldBeInstanceOf<ServerMessage.SeatUpdate>().view
    val aDecides = viewA.pendingDecision is DecisionViewDto.ToDecide
    val deciderView = if (aDecides) viewA else viewB
    return SeatedMatch(
        decider = if (aDecides) a else b,
        deciderRequest = (deciderView.pendingDecision as DecisionViewDto.ToDecide).request,
        other = if (aDecides) b else a,
        otherSeat = if (aDecides) viewB.viewer else viewA.viewer,
        chooser = RandomRemoteAgent(CHOOSER_SEED),
    )
}

/**
 * Decodes a received frame as the schema's [ServerMessage.Error] and returns its `code` string
 * (ADR-008): as of P7.3 a rejection is a first-class server message, not a server-local frame.
 */
private fun errorCodeOf(json: String): String = decodeServerMessage(json).shouldBeInstanceOf<ServerMessage.Error>().code
