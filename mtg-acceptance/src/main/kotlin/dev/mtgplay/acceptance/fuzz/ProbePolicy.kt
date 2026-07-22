package dev.mtgplay.acceptance.fuzz

/**
 * Which decision windows a fuzz run subjects to enumeration-completeness probing (deliverable 2 of
 * P3.3): the sampling knob that trades probe coverage against runtime.
 *
 * Probing every option at every window is the strongest form of the ADR-005 "no phantom options"
 * property, but it multiplies engine work by roughly the average option count, which overruns the
 * fast-default budget for `./gradlew build` (P3.3 measured this — see the packet report). A
 * [Strided] policy that staggers by seed keeps the default-size corpora within budget while still
 * probing every window *position* somewhere across the corpus, and turns into near-total coverage
 * at nightly seed counts.
 *
 * A policy is a pure function of `(seed, windowIndex)` so a fuzz run stays fully reproducible
 * (ADR-006): the same seed probes exactly the same windows on every run, which is what lets a
 * persisted repro replay a probe failure.
 */
sealed interface ProbePolicy {
    /**
     * Whether the window at [windowIndex] (the count of decisions already applied this game) of the
     * game seeded [seed] should be probed. Deterministic in both arguments.
     */
    fun shouldProbe(
        seed: Long,
        windowIndex: Int,
    ): Boolean

    /** Probe every window — the exhaustive policy, reserved for small or nightly-scale runs. */
    data object EveryWindow : ProbePolicy {
        override fun shouldProbe(
            seed: Long,
            windowIndex: Int,
        ): Boolean = true
    }

    /** Probe no window — for measuring the un-probed baseline; not used by the shipped corpora. */
    data object Never : ProbePolicy {
        override fun shouldProbe(
            seed: Long,
            windowIndex: Int,
        ): Boolean = false
    }

    /**
     * Probe one window in every [stride], staggered by seed: window `w` of seed `s` is probed when
     * `(s + w) % stride == 0`. The stagger means seed 0 probes windows 0, stride, 2·stride, …, seed
     * 1 probes windows stride−1, 2·stride−1, …, and so on — so across a corpus of at least [stride]
     * seeds every window position is probed by some seed, while any single game probes only about
     * `1/stride` of its windows.
     *
     * @property stride the probe period; at least one (a stride of one degenerates to
     *   [EveryWindow]).
     */
    data class Strided(
        val stride: Int,
    ) : ProbePolicy {
        init {
            require(stride >= 1) { "probe stride must be at least one, was $stride" }
        }

        override fun shouldProbe(
            seed: Long,
            windowIndex: Int,
        ): Boolean = (seed + windowIndex) % stride == 0L
    }

    companion object {
        /**
         * The stride the shipped corpora use (P3.3): chosen from the measured per-window probe cost
         * so the default-size corpora stay within ~1.5× the un-probed runtime (packet report). At
         * this stride a default corpus still probes hundreds to thousands of windows, and a
         * nightly-scale corpus probes millions.
         */
        const val DEFAULT_STRIDE: Int = 6

        /** The shipped default policy: [Strided] at [DEFAULT_STRIDE]. */
        val DEFAULT: ProbePolicy = Strided(DEFAULT_STRIDE)
    }
}
