package dev.mtgplay.server

import java.io.IOException
import java.net.ServerSocket

/**
 * Probes whether the sandbox permits binding a localhost server socket at all (ADR-008): the shared
 * gate for the real-socket suites. `testApplication` is the primary in-process vehicle; a socket bind
 * is best-effort, so a suite that needs one skips-with-flag rather than failing when it is forbidden.
 */
internal fun canBindLocalhost(): Boolean =
    try {
        ServerSocket(0).use { true }
    } catch (failure: IOException) {
        println("[real-socket] localhost bind probe failed: ${failure.message}")
        false
    }
