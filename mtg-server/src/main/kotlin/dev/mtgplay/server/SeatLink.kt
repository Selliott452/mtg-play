package dev.mtgplay.server

import dev.mtgplay.core.identity.PlayerId

/**
 * A [Match]'s outbound handle to one connected seat (ADR-008): the seam between the pure match model
 * and the WebSocket transport. [Match] speaks only encoded strings through this interface, so it
 * carries no Ktor types and stays unit-testable with an in-memory fake.
 *
 * A link is owned by exactly one connection; the transport implementation ([KtorSeatLink]) forwards
 * [send] onto its session and [supersede] closes it when a newer connection for the same seat wins
 * (latest-wins reconnection, documented on [Match.attach]).
 */
interface SeatLink {
    /** Which seat this link delivers to. */
    val seat: PlayerId

    /** Delivers one already-encoded frame (a [dev.mtgplay.protocol.ServerMessage] or [ServerError]). */
    suspend fun send(frame: String)

    /** Closes this connection because a newer connection for the same seat has taken over. */
    suspend fun supersede()
}
