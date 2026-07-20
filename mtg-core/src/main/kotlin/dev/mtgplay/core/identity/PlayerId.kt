package dev.mtgplay.core.identity

/**
 * A seat at the table, identifying one player for the whole game.
 *
 * A [PlayerId] is the stable handle for a participant: turn order, priority, ownership, and
 * per-seat state filtering (ADR-007) are all keyed by it. The MVP plays two-player games, but
 * the type deliberately does not hardcode two seats — [seat] is an arbitrary non-negative
 * index, so multiplayer stays representable without a type change.
 *
 * @property seat the zero-based seat index; distinct players have distinct seats.
 */
@JvmInline
value class PlayerId(
    val seat: Int,
) {
    init {
        require(seat >= 0) { "seat index must be non-negative, was $seat" }
    }
}
