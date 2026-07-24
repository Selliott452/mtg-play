package dev.mtgplay.server

import dev.mtgplay.protocol.DecisionViewDto
import dev.mtgplay.protocol.ServerMessage
import dev.mtgplay.protocol.decodeServerMessage
import dev.mtgplay.server.client.RandomRemoteAgent
import dev.mtgplay.server.client.SeatRun
import dev.mtgplay.server.client.awaitToDecide
import dev.mtgplay.server.client.nextText
import dev.mtgplay.server.client.playToGameOver
import dev.mtgplay.server.client.sendDecision
import dev.mtgplay.server.client.sendToken
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.testing.testApplication
import io.ktor.websocket.close
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val RECONNECT_SEED: Long = 0x1234
private const val CHOOSER_SEED_A: Long = 7
private const val CHOOSER_SEED_B: Long = 40

/**
 * Reconnection-with-resync (ADR-008): a seat that drops mid-decision reconnects with the same token
 * and is handed exactly the request it was on — pure re-derivation via `pendingRequestOf`/`viewFor`
 * (ADR-004/ADR-007). The server keeps no per-connection memory; the state alone reconstitutes the
 * seat's whole legal view. This is the no-hidden-position payoff: a dropped agent loses nothing.
 */
class ReconnectSpec :
    StringSpec({
        "ADR-007/ADR-008: a dropped deciding seat reconnects to the same pending request and finishes the game" {
            val server = MatchServer()
            val handle = server.createMatch(MvpMatch.config(RECONNECT_SEED))
            testApplication {
                application { matchModule(server) }
                val client = createClient { install(WebSockets) }
                val path = "/matches/${handle.id.value}"
                val tokenA = handle.tokens.getValue(MvpMatch.monoRedSeat)
                val tokenB = handle.tokens.getValue(MvpMatch.boglesSeat)

                lateinit var runA: SeatRun
                lateinit var runB: SeatRun
                coroutineScope {
                    // Seat B plays through normally in the background.
                    val b = client.webSocketSession(path)
                    b.sendToken(tokenB)
                    launch {
                        runB = b.playToGameOver(RandomRemoteAgent(CHOOSER_SEED_B))
                        b.close()
                    }

                    // Seat A connects, waits until it is A's turn, captures the request, then drops
                    // WITHOUT answering — leaving the match paused on A's outstanding request.
                    val firstConnection = client.webSocketSession(path)
                    firstConnection.sendToken(tokenA)
                    val outstanding = firstConnection.awaitToDecide()
                    firstConnection.close()

                    // Seat A reconnects with the same token: the very first frame must be the same
                    // pending request, re-derived from state (no server-side session memory).
                    val reconnection = client.webSocketSession(path)
                    reconnection.sendToken(tokenA)
                    val resynced = decodeServerMessage(reconnection.nextText())
                    val update = resynced.shouldBeInstanceOf<ServerMessage.SeatUpdate>()
                    val toDecide = update.view.pendingDecision.shouldBeInstanceOf<DecisionViewDto.ToDecide>()
                    toDecide.request.id shouldBe outstanding.id

                    // Answer the resynced request and finish the game.
                    val chooserA = RandomRemoteAgent(CHOOSER_SEED_A)
                    reconnection.sendDecision(chooserA.decide(toDecide.request))
                    runA = reconnection.playToGameOver(chooserA)
                    reconnection.close()
                }

                // Both seats reached the same, real result after the reconnection.
                runA.result shouldBe runB.result
            }
        }
    })
