package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.RandomLegalResponder
import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.acceptance.fuzz.FuzzCorpus
import dev.mtgplay.acceptance.fuzz.FuzzHarness
import dev.mtgplay.acceptance.replay.ReplayHarness
import dev.mtgplay.core.event.GameEvent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

// The default matchup-corpus size. Calibrated (P6.3 packet report): across the deterministic seed range
// 0 until N every signature-mechanism floor is met, the binding one being escape (Sentinel's Eyes, a 1-of
// that needs a graveyard-cast setup) whose first random-legal occurrence is seed 49; every other floor
// appears by seed 11. A default of 100 clears them all with margin (~7 escapes in 100) while keeping the
// `./gradlew build` runtime sane at ~70-135ms/seed warm. Nightly scales the whole corpus via `-PfuzzSeeds`.
private const val MATCHUP_SEEDS: Int = 100

// Per-game turn bound. A no-death real-card game decks out near turn 108; this cap tolerates the grind while
// a game still going at it is a stall (INCONCLUSIVE), not a failure (CR imposes no turn limit).
private const val MATCHUP_TURN_CAP: Int = REAL_CARD_TURN_CAP

// Runaway guard on decisions per game, far above any real MVP game's decision count.
private const val MATCHUP_DECISION_CAP: Int = 60_000

/**
 * The MVP matchup corpus (P6.3, deliverable 1): the flagship validation corpus — both real 75 mainboards
 * (Mono-Red Madness at seat [alice], GW Bogles at seat [bob]), mulligans on, pure random-legal play — driven
 * through the [FuzzHarness] across [MATCHUP_SEEDS] seeds (scaled by `-PfuzzSeeds`).
 *
 * The fuzz contract (PLAN.md §2.3) is the backbone: the harness invariant-checks **every** transition and
 * enumeration-completeness-probes sampled windows at the standard stride, aborting with a persisted repro on
 * any violation, exception, or phantom option — so a returned [dev.mtgplay.acceptance.fuzz.CorpusReport] is
 * itself proof of zero violations over the corpus. On top of that this suite asserts the matchup's *positive*
 * signature: the termination taxonomy is fully accounted (decisive deck-out / life-loss vs tolerated
 * stall-capped INCONCLUSIVE), and every one of the fifteen [Mechanism] floors is met at least once across the
 * corpus — the two decks' whole mechanical surface actually fires under random play, not just in the scripted
 * per-card specs. All randomness flows through the seeded core `Rng` (ADR-006).
 */
class MvpMatchupCorpusSpec :
    StringSpec({
        val seeds = fuzzSeeds(MATCHUP_SEEDS)
        "${seeds.size} seeds of the real Madness-vs-Bogles matchup: no violations, all mechanism floors met" {
            val census = MechanismCensus()
            val startNanos = System.nanoTime()
            val report =
                FuzzHarness.run(
                    FuzzCorpus(
                        name = "mvp-matchup",
                        seeds = seeds,
                        configForSeed = { seed -> mvpMatchupConfig(seed) },
                        caps = FuzzCorpus.Caps(turnCap = MATCHUP_TURN_CAP, decisionCap = MATCHUP_DECISION_CAP),
                    ),
                ) { game, _ -> census.record(mechanismsIn(game)) }
            val elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000
            val perSeedMillis = if (seeds.isNotEmpty()) elapsedMillis / seeds.size else 0

            // Full taxonomy + mechanism census + runtime for the packet report / nightly log (deliverable 5).
            println(
                "MVP MATCHUP CORPUS ${report.summary()}; runtime ${elapsedMillis}ms (~${perSeedMillis}ms/seed); " +
                    "mechanisms: ${census.report()}",
            )

            // Every seed is accounted for as decisive or a tolerated stall (the harness guarantees no failure).
            (report.decisive + report.inconclusive) shouldBe report.seedCount
            // At least one seed ran the two real decks to a genuine conclusion — a real corpus, not all stalls.
            (report.decisive >= 1) shouldBe true
            // Every signature mechanism of the matchup fired at least once under pure random play (deliverable 1).
            census.unmetFloors().shouldBeEmpty()
        }

        "ADR-006: a corpus game with mulligans, madness, and combat replays to an identical fingerprint and log" {
            // The ADR-006 guarantee on the *real* matchup at scale (deliverable 3): a full random-legal game
            // exercising the whole hard surface — a pre-game mulligan, a madness cast, and a combat death —
            // recorded as (config, decisions) and replayed to a byte-identical final state and event log.
            val (seed, original) = firstMatchupGameWith(REPLAY_SEARCH_BOUND)
            println("MVP MATCHUP REPLAY seed $seed: mulligan+madness+combat, ${original.decisions.size} decisions")

            val outcome = ReplayHarness.verifyReproduces(mvpMatchupConfig(seed), original)
            outcome.fingerprintMatches.shouldBeTrue()
            outcome.eventLogMatches.shouldBeTrue()
        }
    })

// The seed range searched for the replay game. The first seed exercising all three of mulligan, madness, and
// combat is small; the lazy search stops there, so the cost is a handful of playouts, not the whole range.
private const val REPLAY_SEARCH_BOUND: Long = 400L

/**
 * The first seed in `0 until [bound]` whose random-legal matchup game takes a mulligan, casts a madness card,
 * and kills a creature in combat, paired with the finished (played-out) game. Deterministic: the engine and
 * responder are pure functions of the seed (ADR-006), so this both selects a rich replay subject and
 * documents its seed.
 */
private fun firstMatchupGameWith(bound: Long): Pair<Long, ScriptedGame> {
    for (seed in 0L until bound) {
        val game =
            ScriptedGame
                .start(mvpMatchupConfig(seed))
                .playUntilOverOrBound(
                    RandomLegalResponder(seed),
                    turnCap = MATCHUP_TURN_CAP,
                    maxDecisions = MATCHUP_DECISION_CAP,
                )
        val events = game.state.events
        val rich =
            events.any { it is GameEvent.MulliganTaken } &&
                Mechanism.MADNESS_CAST in mechanismsIn(game) &&
                events.any { it is GameEvent.CreatureDied }
        if (rich) return seed to game
    }
    error("no matchup seed in 0 until $bound produced a mulligan + madness + combat game")
}
