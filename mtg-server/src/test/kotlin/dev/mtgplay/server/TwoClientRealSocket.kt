package dev.mtgplay.server

import dev.mtgplay.server.client.RandomRemoteAgent
import dev.mtgplay.server.client.ReferenceClient
import dev.mtgplay.server.client.SeatRun
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal const val LOOPBACK: String = "127.0.0.1"
private const val STOP_GRACE_MS: Long = 100
private const val STOP_TIMEOUT_MS: Long = 1000

/**
 * Plays the MVP match end-to-end with a real CIO server on an ephemeral localhost port and TWO real
 * [ReferenceClient]s — each on its own real socket — driven by seeded [RandomRemoteAgent]s (ADR-008).
 * Returns the two seats' [SeatRun]s. This is the exact code path the two-process acceptance runs, minus
 * the per-JVM startup: it validates the reference client over an actual socket, in one JVM.
 *
 * Shared by the always-on [TwoClientRealSocketSpec] and the [TwoProcessGameSpec] fallback so the wire
 * playthrough is written once.
 */
internal suspend fun playTwoClientRealSocketMatch(
    matchSeed: Long,
    seedA: Long,
    seedB: Long,
): Pair<SeatRun, SeatRun> {
    val server = MatchServer()
    val handle = server.createMatch(MvpMatch.config(matchSeed))
    val embedded = embeddedServer(CIO, host = LOOPBACK, port = 0) { matchModule(server) }
    embedded.start(wait = false)
    return try {
        val port =
            embedded.engine
                .resolvedConnectors()
                .first()
                .port
        val tokenA = handle.tokens.getValue(MvpMatch.monoRedSeat).value
        val tokenB = handle.tokens.getValue(MvpMatch.boglesSeat).value
        coroutineScope {
            val a = async { ReferenceClient(LOOPBACK, port, handle.id.value, tokenA).play(RandomRemoteAgent(seedA)) }
            val b = async { ReferenceClient(LOOPBACK, port, handle.id.value, tokenB).play(RandomRemoteAgent(seedB)) }
            val results = awaitAll(a, b)
            results[0] to results[1]
        }
    } finally {
        embedded.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS)
    }
}
