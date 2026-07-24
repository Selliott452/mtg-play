package dev.mtgplay.server

import dev.mtgplay.protocol.toDto
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.server.client.RandomRemoteAgent
import dev.mtgplay.server.client.SeatRun
import dev.mtgplay.server.client.playToGameOver
import dev.mtgplay.server.client.sendToken
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.close
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val FULL_GAME_SEED: Long = 0x7A2B
private const val CHOOSER_SEED_A: Long = 11
private const val CHOOSER_SEED_B: Long = 29

/**
 * The packet's headline acceptance (ADR-008): two schema-only wire clients play a complete real-deck
 * game (Mono-Red Madness vs GW Bogles) end to end over `testApplication`, and a second suite pins the
 * determinism the wire inherits from the engine (ADR-006).
 */
class FullWireGameSpec :
    StringSpec({
        "ADR-008: two schema-only wire clients play a full real-deck game to a consistent, engine-true GameOver" {
            val server = MatchServer()
            val handle = server.createMatch(MvpMatch.config(FULL_GAME_SEED))
            testApplication {
                application { matchModule(server) }
                val (runA, runB) = playWireMatch(handle, CHOOSER_SEED_A, CHOOSER_SEED_B)

                // A real game happened: envelopes flowed and decisions were made over the wire only.
                runA.envelopes shouldBeGreaterThan 0
                runB.envelopes shouldBeGreaterThan 0
                (runA.decisionsSent + runB.decisionsSent) shouldBeGreaterThan 0

                // Every received envelope carried the protocol version (ADR-008: schema-skew is loud).
                runA.allEnvelopesVersioned shouldBe true
                runB.allEnvelopesVersioned shouldBe true

                // Both seats saw the same outcome, and it is the engine's own result.
                runA.result shouldBe runB.result
                val match = server.registry.find(handle.id).shouldNotBeNull()
                val over = match.currentResult().shouldBeInstanceOf<AdvanceResult.GameOver>()
                runA.result shouldBe over.result.toDto()
            }
        }

        "ADR-006: the same match seed and client seeds produce the same winner and final-request count twice" {
            val first = playDeterministicMatch()
            val second = playDeterministicMatch()
            first shouldBe second
        }
    })

/** One match's determinism fingerprint: the winning seat and the total number of decisions answered. */
private data class MatchFingerprint(
    val winner: Int,
    val totalDecisions: Int,
)

private suspend fun playDeterministicMatch(): MatchFingerprint {
    val server = MatchServer()
    val handle = server.createMatch(MvpMatch.config(FULL_GAME_SEED))
    var fingerprint: MatchFingerprint? = null
    testApplication {
        application { matchModule(server) }
        val (runA, runB) = playWireMatch(handle, CHOOSER_SEED_A, CHOOSER_SEED_B)
        fingerprint = MatchFingerprint(runA.result.winner, runA.decisionsSent + runB.decisionsSent)
    }
    return fingerprint ?: error("the deterministic match did not complete")
}

/** Opens both seats, plays each with its own seeded chooser, and returns their [SeatRun]s. */
private suspend fun ApplicationTestBuilder.playWireMatch(
    handle: MatchHandle,
    chooserSeedA: Long,
    chooserSeedB: Long,
): Pair<SeatRun, SeatRun> {
    val client = createClient { install(WebSockets) }
    val path = "/matches/${handle.id.value}"
    val a = client.webSocketSession(path)
    val b = client.webSocketSession(path)
    a.sendToken(handle.tokens.getValue(MvpMatch.monoRedSeat))
    b.sendToken(handle.tokens.getValue(MvpMatch.boglesSeat))
    lateinit var runA: SeatRun
    lateinit var runB: SeatRun
    coroutineScope {
        launch { runA = a.playToGameOver(RandomRemoteAgent(chooserSeedA)) }
        launch { runB = b.playToGameOver(RandomRemoteAgent(chooserSeedB)) }
    }
    a.close()
    b.close()
    return runA to runB
}
