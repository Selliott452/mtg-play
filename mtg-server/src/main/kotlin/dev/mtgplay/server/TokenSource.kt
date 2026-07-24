package dev.mtgplay.server

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.Rng

/**
 * Mints the per-seat [SeatToken]s for a new match (ADR-008). Abstracted so a real deployment can
 * inject an unpredictable source (e.g. one backed by `java.security.SecureRandom`, which is *not*
 * the detekt-banned `java.util.Random`) while the reference server defaults to a deterministic,
 * seed-derived source so a `(seed)` reproduces a whole match — tokens included — for tests and
 * replay (ADR-006).
 *
 * The determinism ban (ADR-006) applies here too: no `kotlin.random`/`java.util.Random`. The
 * default [SeededTokenSource] draws from the in-repo splitmix64 [Rng].
 */
fun interface TokenSource {
    /**
     * Mints one distinct token per seat, in the order of [seats]. The result must contain exactly
     * [seats] as keys and hold no duplicate token values (a shared token would let either client
     * claim either seat).
     */
    fun mint(
        seed: Long,
        seats: List<PlayerId>,
    ): Map<PlayerId, SeatToken>
}

/**
 * The reference server's default [TokenSource]: tokens are derived deterministically from the match
 * seed via the seeded [Rng] (ADR-006), so a match — its tokens included — is reproducible from the
 * seed alone. splitmix64's mixing is a bijection on 64 bits, so the consecutive draws it hands each
 * seat are guaranteed distinct; the [require] documents (and pins) that guarantee.
 *
 * Deterministic tokens are exactly what a training/replay harness wants; they are *not* suitable for
 * an adversarial deployment, which injects an unpredictable [TokenSource] instead.
 */
object SeededTokenSource : TokenSource {
    private const val HEX_RADIX: Int = 16
    private const val TOKEN_LENGTH: Int = 16

    override fun mint(
        seed: Long,
        seats: List<PlayerId>,
    ): Map<PlayerId, SeatToken> {
        val tokens =
            buildMap {
                var rng = Rng(seed)
                for (seat in seats) {
                    val (raw, next) = rng.nextLong()
                    rng = next
                    put(seat, SeatToken(raw.toULong().toString(HEX_RADIX).padStart(TOKEN_LENGTH, '0')))
                }
            }
        require(tokens.values.toSet().size == seats.size) {
            "seeded tokens collided for seed $seed across seats $seats — splitmix64 bijection violated"
        }
        return tokens
    }
}
