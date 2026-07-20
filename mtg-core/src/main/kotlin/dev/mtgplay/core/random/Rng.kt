package dev.mtgplay.core.random

private const val GOLDEN_GAMMA: ULong = 0x9E37_79B9_7F4A_7C15uL
private const val MIX_MULTIPLIER_A: ULong = 0xBF58_476D_1CE4_E5B9uL
private const val MIX_MULTIPLIER_B: ULong = 0x94D0_49BB_1331_11EBuL
private const val MIX_SHIFT_A: Int = 30
private const val MIX_SHIFT_B: Int = 27
private const val MIX_SHIFT_C: Int = 31

/**
 * The deterministic, pure-functional PRNG all in-game randomness flows through (ADR-006).
 *
 * **The algorithm is a frozen contract.** This is an in-repo implementation of splitmix64
 * (Sebastiano Vigna's public-domain reference, `https://prng.di.unimi.it/splitmix64.c`), with
 * no dependency on `kotlin.random` or `java.util.Random` at all: platform RNGs do not
 * guarantee algorithm stability across versions, and recorded replay corpora (ADR-006 — a
 * replay is seed + decisions) must stay valid forever. Any change to the constants or mixing
 * below silently invalidates every recorded game, which is why the known-answer tests pin the
 * published output vectors: an accidental change breaks the build, not the corpora.
 *
 * Pure-functional shape: every draw returns the value *and* the successor generator; an [Rng]
 * value is never advanced in place (ADR-002). The current generator lives in
 * [dev.mtgplay.core.state.GameState.rng].
 *
 * @property state the current 64-bit generator state — the seed, for a fresh generator.
 */
@JvmInline
value class Rng(
    val state: Long,
) {
    /**
     * Draws the next 64-bit value: advances the state by the golden-gamma increment and mixes
     * it (splitmix64), returning the output and the successor generator.
     */
    fun nextLong(): Pair<Long, Rng> {
        val advanced = state.toULong() + GOLDEN_GAMMA
        var mixed = advanced
        mixed = (mixed xor (mixed shr MIX_SHIFT_A)) * MIX_MULTIPLIER_A
        mixed = (mixed xor (mixed shr MIX_SHIFT_B)) * MIX_MULTIPLIER_B
        mixed = mixed xor (mixed shr MIX_SHIFT_C)
        return mixed.toLong() to Rng(advanced.toLong())
    }

    /**
     * Draws a uniformly distributed value in `[0, bound)` plus the successor generator, by
     * unbiased rejection sampling over [nextLong]. The rejection scheme — reject outputs below
     * `2^64 mod bound`, then reduce modulo `bound` — is part of the frozen contract, for the
     * same replay-stability reason as the core algorithm.
     */
    fun nextInt(bound: Int): Pair<Int, Rng> {
        require(bound > 0) { "bound must be positive, was $bound" }
        val unsignedBound = bound.toULong()
        // 2^64 mod bound, computed as (2^64 - bound) mod bound in wrapping unsigned arithmetic.
        val rejectBelow = (0uL - unsignedBound) % unsignedBound
        var current = this
        while (true) {
            val (raw, successor) = current.nextLong()
            current = successor
            val value = raw.toULong()
            if (value >= rejectBelow) return (value % unsignedBound).toInt() to current
        }
    }
}
