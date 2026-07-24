package dev.mtgplay.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer

private const val DEFAULT_HOST: String = "127.0.0.1"
private const val DEFAULT_PORT: Int = 8080
private const val DEFAULT_SEED: Long = 0L

/**
 * The runnable reference server (ADR-008): `./gradlew :mtg-server:run --args="<host> <port> <seed>"`
 * hosts one MVP match (Mono-Red Madness vs GW Bogles, [MvpMatch]). All three args are optional and
 * fall back to [DEFAULT_HOST]/[DEFAULT_PORT]/[DEFAULT_SEED].
 *
 * The match id and both seat tokens are printed on startup so a schema-speaking client can connect:
 * `ws://<host>:<port>/matches/<id>`, sending its seat's token as the first text frame. Under the
 * default [SeededTokenSource] the tokens are reproducible from the seed (ADR-006).
 */
fun main(args: Array<String>) {
    val host = args.getOrNull(0) ?: DEFAULT_HOST
    val port = args.getOrNull(1)?.toIntOrNull() ?: DEFAULT_PORT
    val seed = args.getOrNull(2)?.toLongOrNull() ?: DEFAULT_SEED

    val server = MatchServer()
    val handle = server.createMatch(MvpMatch.config(seed))

    println("mtg-play reference server (ADR-008)")
    println("match ${handle.id.value}  seed ${handle.seed}")
    handle.tokens.toSortedMap(compareBy { it.seat }).forEach { (seat, token) ->
        println("  seat ${seat.seat} token ${token.value}")
    }
    println("listening on ws://$host:$port/matches/${handle.id.value}")

    embeddedServer(CIO, host = host, port = port) {
        matchModule(server)
    }.start(wait = true)
}
