package dev.mtgplay.acceptance.fuzz

import dev.mtgplay.acceptance.driver.RandomLegalResponder
import dev.mtgplay.acceptance.driver.Responder
import dev.mtgplay.rules.MatchConfig

/**
 * The definition of one fuzz corpus (deliverable 1 of P3.3): a family of random playouts the
 * [FuzzHarness] runs as a batch, unifying what the P2.x/P3.x smoke specs previously hand-rolled per
 * spec.
 *
 * A corpus is card-agnostic: it names the match to play *for each seed* via [configForSeed] and the
 * responder to drive it via [responderForSeed], rather than referencing any specific card, so the
 * harness and this type stay in `mtg-acceptance` main source (the module's card-agnostic charter)
 * while the concrete deck shapes live in the test-source suites that build the configs. The "deck
 * shapes per seat, seed range, turn cap, responder" of the packet spec are exactly: the libraries
 * inside each [configForSeed] result, the [seeds], the [caps], and [responderForSeed].
 *
 * @property name a human-readable corpus name, used in reports and repro files.
 * @property seeds the seeds to play, one game each (ADR-006). Determines corpus size.
 * @property configForSeed the match to start for a given seed — the per-seat decks, the seed itself,
 *   the definition registry. Must embed [seed] as its `MatchConfig.seed` so replay is faithful.
 * @property responderForSeed the decision policy for a given seed; [randomLegal] gives each seed its
 *   own seeded [RandomLegalResponder] (ADR-005, ADR-006).
 * @property caps the per-game termination bounds (turn and decision).
 * @property probePolicy which decision windows to subject to the enumeration-completeness probe
 *   (deliverable 2); the shipped corpora use [ProbePolicy.DEFAULT].
 */
class FuzzCorpus(
    val name: String,
    val seeds: List<Long>,
    val configForSeed: (Long) -> MatchConfig,
    val caps: Caps,
    val responderForSeed: (Long) -> Responder = randomLegal,
    val probePolicy: ProbePolicy = ProbePolicy.DEFAULT,
) {
    /** The per-game turn bound. */
    val turnCap: Int get() = caps.turnCap

    /** The per-game runaway decision guard. */
    val decisionCap: Int get() = caps.decisionCap

    /**
     * The per-game termination bounds. A game still going at [turnCap] is classified
     * [Outcome.INCONCLUSIVE] — a board stall is not a failure, since the CR imposes no turn limit;
     * [decisionCap] is a runaway guard far above any real game's decision count.
     *
     * @property turnCap the turn bound.
     * @property decisionCap the decision bound.
     */
    data class Caps(
        val turnCap: Int,
        val decisionCap: Int = DEFAULT_DECISION_CAP,
    )

    companion object {
        /** The default runaway decision guard: far above any MVP-pool game's decision count. */
        const val DEFAULT_DECISION_CAP: Int = 100_000

        /**
         * A [responderForSeed] that gives each seed its own seeded [RandomLegalResponder] — the
         * uniform-random-legal policy at the heart of the fuzz rig (ADR-005, ADR-006).
         */
        val randomLegal: (Long) -> Responder = { seed -> RandomLegalResponder(seed) }
    }
}
