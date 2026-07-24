package dev.mtgplay.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking

private const val DEFAULT_HOST: String = "127.0.0.1"
private const val DEFAULT_PORT: Int = 8080
private const val DEFAULT_SEED: Long = 0L

/**
 * The runnable reference server (ADR-008): hosts one MVP match (Mono-Red Madness vs GW Bogles,
 * [MvpMatch]) and pumps the wire protocol. Launch either way:
 *
 * - `./gradlew :mtg-server:run --args="--host 127.0.0.1 --port 0 --seed 42"`
 * - `java -cp <runtime classpath> dev.mtgplay.server.ServerMainKt --host 127.0.0.1 --port 0 --seed 42`
 *
 * Flags: `--host` (default [DEFAULT_HOST]), `--port` (default [DEFAULT_PORT]; **`0` binds an ephemeral
 * OS-chosen port**), `--seed` (default [DEFAULT_SEED]). Under the default [SeededTokenSource] the whole
 * match — tokens included — is reproducible from the seed (ADR-006).
 *
 * On startup it prints the **actual bound port**, the match id, and both seat tokens, ending with one
 * parseable line the two-process acceptance reads:
 * `SERVER_READY port=<p> match=<id> seat0=<token> seat1=<token>`. A schema-speaking client then connects
 * to `ws://<host>:<p>/matches/<id>`, sending its seat's token as the first text frame. The process runs
 * until it is terminated (it hosts one match and does not self-stop).
 */
fun main(args: Array<String>) {
    val options = parseFlags(args)
    val host = options["host"] ?: DEFAULT_HOST
    val port = options["port"]?.toIntOrNull() ?: DEFAULT_PORT
    val seed = options["seed"]?.toLongOrNull() ?: DEFAULT_SEED

    val server = MatchServer()
    val handle = server.createMatch(MvpMatch.config(seed))

    val embedded = embeddedServer(CIO, host = host, port = port) { matchModule(server) }
    embedded.start(wait = false)
    // Resolve the port the engine actually bound (essential when --port 0 lets the OS choose).
    val boundPort =
        runBlocking {
            embedded.engine
                .resolvedConnectors()
                .first()
                .port
        }

    printStartupBanner(host, boundPort, handle)

    // One hosted match, no self-stop: block until the process is terminated by its launcher.
    runBlocking { awaitCancellation() }
}

/** Prints the human-readable banner and the machine-parseable `SERVER_READY` line, then flushes stdout. */
private fun printStartupBanner(
    host: String,
    boundPort: Int,
    handle: MatchHandle,
) {
    val tokensBySeat = handle.tokens.toSortedMap(compareBy { it.seat })
    println("mtg-play reference server (ADR-008)")
    println("match ${handle.id.value}  seed ${handle.seed}")
    tokensBySeat.forEach { (seat, token) -> println("  seat ${seat.seat} token ${token.value}") }
    println("listening on ws://$host:$boundPort/matches/${handle.id.value}")

    val tokenPart = tokensBySeat.entries.joinToString(" ") { (seat, token) -> "seat${seat.seat}=${token.value}" }
    println("SERVER_READY port=$boundPort match=${handle.id.value} $tokenPart")
    System.out.flush()
}

/** Parses `--key value` pairs into a map; a bare flag or a missing value fails loudly. */
private fun parseFlags(args: Array<String>): Map<String, String> {
    val options = mutableMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val flag = args[index]
        require(flag.startsWith("--")) { "expected a --flag, got '$flag'" }
        require(index + 1 < args.size) { "flag '$flag' needs a value" }
        options[flag.removePrefix("--")] = args[index + 1]
        index += 2
    }
    return options
}
