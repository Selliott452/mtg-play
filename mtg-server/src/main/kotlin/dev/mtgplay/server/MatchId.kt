package dev.mtgplay.server

/**
 * The in-memory handle for one hosted match (ADR-008): the id a client puts in its connect route to
 * reach a specific match in the [MatchRegistry]. Opaque and server-minted; not derived from the seed
 * (two matches may share a seed yet must be addressable separately).
 *
 * @property value the id string; compared for exact equality and carried in the WebSocket route.
 */
@JvmInline
value class MatchId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "a match id cannot be blank" }
    }
}
