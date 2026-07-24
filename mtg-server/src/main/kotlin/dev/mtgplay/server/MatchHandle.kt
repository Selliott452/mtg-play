package dev.mtgplay.server

import dev.mtgplay.core.identity.PlayerId

/**
 * What creating a match hands back (ADR-008): the [id] to address it and the per-seat [tokens] to
 * authenticate the two connections, plus the [seed] the match was started from — exposed so a caller
 * can reproduce the exact game deterministically (ADR-006).
 *
 * @property id the registry handle a client puts in its connect route.
 * @property seed the match PRNG seed (ADR-006); the whole game — and, under [SeededTokenSource], the
 *   tokens themselves — reproduces from it.
 * @property tokens the bearer token for each seat; a client presents its seat's token on connect.
 */
data class MatchHandle(
    val id: MatchId,
    val seed: Long,
    val tokens: Map<PlayerId, SeatToken>,
)
