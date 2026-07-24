package dev.mtgplay.server.client

import dev.mtgplay.protocol.ServerMessage

/**
 * A [ServerMessage.Error] surfaced as an exception (ADR-008): the server rejected a frame this client
 * sent. A [RandomRemoteAgent] only ever sends engine-legal decisions, so reaching this is a genuine
 * fault — a version skew, a protocol bug, or a hand-broken frame — and the reference client fails
 * loudly rather than looping (CONVENTIONS: never silently approximate).
 *
 * @property code the server's machine-readable category (the reference server writes a `ServerErrorCode` name).
 * @property detail the server's human-readable explanation.
 */
class RemoteError(
    val code: String,
    val detail: String,
) : RuntimeException("server rejected a frame [$code]: $detail")
