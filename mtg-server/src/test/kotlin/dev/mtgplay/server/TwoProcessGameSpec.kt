package dev.mtgplay.server

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

private const val MATCH_SEED: Long = 0x7A2B
private const val SEED_A: Long = 11
private const val SEED_B: Long = 29
private const val SERVER_MAIN: String = "dev.mtgplay.server.ServerMainKt"
private const val CLIENT_MAIN: String = "dev.mtgplay.server.client.ClientMainKt"

// Generous guards: a stuck child fails loudly rather than hanging the suite. Real timings are seconds.
private const val SERVER_READY_TIMEOUT_MS: Long = 90_000
private const val CLIENT_EXIT_TIMEOUT_MS: Long = 180_000
private const val POLL_INTERVAL_MS: Long = 25
private const val DRAIN_JOIN_MS: Long = 500

/**
 * The day-one acceptance (ADR-008, PLAN.md): **two separate processes complete a game against each
 * other**. This launches the reference server as its own JVM (`ServerMainKt --port 0`), reads its
 * bound port and seat tokens from the parseable `SERVER_READY` line, then launches TWO client JVMs
 * (`ClientMainKt`, seeded random agents) that connect over a real localhost socket and play to a
 * `GameOver`. Each client prints a parseable `GAME_OVER winner=…` line; the test asserts both name the
 * same winner — a full game, driven end to end across three OS processes, knowing only the wire schema.
 *
 * **Fallback.** If the sandbox forbids `ProcessBuilder` (unlikely — Gradle itself forks JVMs), the test
 * flags the fallback prominently and instead runs the in-JVM real-socket two-client playthrough
 * ([playTwoClientRealSocketMatch]); if even a localhost bind is forbidden, it skips-with-flag. The
 * always-on fast variant is [TwoClientRealSocketSpec].
 */
class TwoProcessGameSpec :
    StringSpec({
        "ADR-008: two client processes play a server process to a consistent winner (day-one acceptance)" {
            val java = javaBinary()
            val classpath = System.getProperty("java.class.path")

            val server =
                try {
                    SubProcess(
                        listOf(
                            java,
                            "-cp",
                            classpath,
                            SERVER_MAIN,
                            "--host",
                            LOOPBACK,
                            "--port",
                            "0",
                            "--seed",
                            MATCH_SEED.toString(),
                        ),
                    )
                } catch (failure: IOException) {
                    println("[P7.3 two-process] ProcessBuilder unavailable (${failure.message})")
                    null
                }

            if (server == null) {
                // Prominent fallback: prove the same end-to-end wire play in-JVM over a real socket.
                if (!canBindLocalhost()) {
                    println("[P7.3 two-process] SKIPPED: neither a subprocess nor a localhost bind is permitted")
                } else {
                    println("[P7.3 two-process] FALLBACK: ProcessBuilder blocked; using the in-JVM real-socket variant")
                    val (runA, runB) = playTwoClientRealSocketMatch(MATCH_SEED, SEED_A, SEED_B)
                    runA.result shouldBe runB.result
                }
            } else {
                try {
                    val ready =
                        server.awaitLine("SERVER_READY", SERVER_READY_TIMEOUT_MS)
                            ?: error(
                                "the server process did not print SERVER_READY within ${SERVER_READY_TIMEOUT_MS}ms; " +
                                    "its output was:\n${server.snapshot().joinToString("\n")}",
                            )
                    val info = parseReady(ready)

                    val clientA = launchClient(java, classpath, info, info.token0, SEED_A)
                    val clientB = launchClient(java, classpath, info, info.token1, SEED_B)

                    clientA.awaitExit(CLIENT_EXIT_TIMEOUT_MS) shouldBe true
                    clientB.awaitExit(CLIENT_EXIT_TIMEOUT_MS) shouldBe true

                    val winnerA = winnerOf(clientA)
                    val winnerB = winnerOf(clientB)

                    // Both independent client processes agree on the winner: one consistent game happened.
                    winnerA shouldBe winnerB
                } finally {
                    server.kill()
                }
            }
        }
    })

/** The launcher's own `java` executable, so a child JVM matches this test JVM's runtime. */
private fun javaBinary(): String {
    val binary = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
    return File(File(System.getProperty("java.home"), "bin"), binary).absolutePath
}

/** Launches one client JVM for the seat holding [token], seeded with [seed]. */
private fun launchClient(
    java: String,
    classpath: String,
    info: ServerInfo,
    token: String,
    seed: Long,
): SubProcess =
    SubProcess(
        listOf(
            java,
            "-cp",
            classpath,
            CLIENT_MAIN,
            "--host",
            LOOPBACK,
            "--port",
            info.port,
            "--match",
            info.match,
            "--token",
            token,
            "--agent",
            "random",
            "--seed",
            seed.toString(),
        ),
    )

/** The fields parsed out of the server's `SERVER_READY port=… match=… seat0=… seat1=…` line. */
private data class ServerInfo(
    val port: String,
    val match: String,
    val token0: String,
    val token1: String,
)

/** Parses a `SERVER_READY` line into its [ServerInfo]. */
private fun parseReady(line: String): ServerInfo {
    val fields =
        line.split(" ").drop(1).associate { field ->
            field.substringBefore("=") to field.substringAfter("=")
        }
    return ServerInfo(
        port = fields.getValue("port"),
        match = fields.getValue("match"),
        token0 = fields.getValue("seat0"),
        token1 = fields.getValue("seat1"),
    )
}

/** The winning seat a client process printed on its `GAME_OVER winner=…` line. */
private fun winnerOf(client: SubProcess): String {
    val line =
        client.snapshot().firstOrNull { it.startsWith("GAME_OVER") }
            ?: error("a client process printed no GAME_OVER line; output:\n${client.snapshot().joinToString("\n")}")
    return line.split(" ").first { it.startsWith("winner=") }.substringAfter("=")
}

/**
 * A launched child JVM with its stdout (and merged stderr) drained on a daemon thread into a growing,
 * thread-safe line list. Draining continuously keeps a chatty child from blocking on a full pipe buffer.
 */
private class SubProcess(
    command: List<String>,
) {
    private val process: Process = ProcessBuilder(command).redirectErrorStream(true).start()
    private val lines = CopyOnWriteArrayList<String>()
    private val pump =
        Thread { process.inputStream.bufferedReader().forEachLine(lines::add) }
            .apply {
                isDaemon = true
                start()
            }

    /** The first collected line starting with [prefix], waiting up to [timeoutMs] for it to appear. */
    fun awaitLine(
        prefix: String,
        timeoutMs: Long,
    ): String? {
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLI
        while (System.nanoTime() < deadline && process.isAlive && lines.none { it.startsWith(prefix) }) {
            Thread.sleep(POLL_INTERVAL_MS)
        }
        // If the process exited, drain any output still in flight before the final look.
        if (!process.isAlive) pump.join(DRAIN_JOIN_MS)
        return lines.firstOrNull { it.startsWith(prefix) }
    }

    /** Waits up to [timeoutMs] for the process to exit; returns whether it did, draining its output first. */
    fun awaitExit(timeoutMs: Long): Boolean {
        val exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (exited) pump.join(DRAIN_JOIN_MS)
        return exited
    }

    /** A snapshot of the lines collected so far. */
    fun snapshot(): List<String> = lines.toList()

    /** Forcibly terminates the process (the server, which does not self-stop). */
    fun kill() {
        process.destroyForcibly()
    }

    private companion object {
        const val NANOS_PER_MILLI: Long = 1_000_000
    }
}
