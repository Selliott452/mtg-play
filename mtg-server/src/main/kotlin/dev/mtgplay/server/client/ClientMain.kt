package dev.mtgplay.server.client

import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

private const val DEFAULT_HOST: String = "127.0.0.1"
private const val DEFAULT_AGENT: String = "random"
private const val DEFAULT_SEED: Long = 0L
private const val RANDOM_AGENT: String = "random"

/**
 * The runnable reference client (ADR-008): connects one seat to a running reference server and plays it
 * to the end with a [RemoteAgent]. Two ways to launch it, both documented here because the `application`
 * plugin binds a single mainClass (the server):
 *
 * - `./gradlew :mtg-server:runClient --args="--host H --port P --match M --token T --seed N"`
 * - `java -cp <cp> dev.mtgplay.server.client.ClientMainKt --host H --port P --match M --token T --seed N`
 *
 * Flags: `--host` (default [DEFAULT_HOST]), `--port` (required), `--match` (required), `--token`
 * (required), `--agent` (default `random`, the only kind), `--seed` (default 0, the agent's PRNG seed,
 * ADR-006). On the game's end it prints one parseable line to stdout:
 * `GAME_OVER winner=<seat> loser=<seat> reason=<REASON> decisions=<n>` — the two-process acceptance
 * parses `winner=` from it. A server rejection surfaces as a [RemoteError] and exits non-zero.
 */
fun main(args: Array<String>) {
    val options = parseFlags(args)
    val host = options["host"] ?: DEFAULT_HOST
    val port = options["port"]?.toIntOrNull() ?: error("--port <int> is required")
    val matchId = options["match"] ?: error("--match <id> is required")
    val token = options["token"] ?: error("--token <token> is required")
    val agent = agentFor(options["agent"] ?: DEFAULT_AGENT, options["seed"]?.toLongOrNull() ?: DEFAULT_SEED)

    val run = runBlocking { ReferenceClient(host, port, matchId, token).play(agent) }
    val result = run.result
    println(
        "GAME_OVER winner=${result.winner} loser=${result.loser} " +
            "reason=${result.reason} decisions=${run.decisionsSent}",
    )
    // Exit promptly and cleanly once the outcome is printed, so a launcher (the two-process acceptance)
    // sees a deterministic exit rather than waiting on lingering client-engine threads.
    exitProcess(0)
}

/** The agent named [kind], seeded with [seed]. Only `random` ([RandomRemoteAgent]) is supported. */
private fun agentFor(
    kind: String,
    seed: Long,
): RemoteAgent =
    when (kind) {
        RANDOM_AGENT -> RandomRemoteAgent(seed)
        else -> error("unknown --agent '$kind'; supported: $RANDOM_AGENT")
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
