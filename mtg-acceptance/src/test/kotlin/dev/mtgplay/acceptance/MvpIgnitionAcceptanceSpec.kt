package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.RandomLegalResponder
import dev.mtgplay.acceptance.driver.ScriptedGame
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan

/**
 * The P6.2c pure-random ignition corpus (deliverable 5): real Mono-Red-Madness-vs-GW-Bogles games driven by
 * a **pure** [RandomLegalResponder] — both decks fully defined, mulligans on, and **no gap-avoidance
 * anywhere** (the four architect gaps are closed, so the retired `GapAvoidingResponder` is gone). Every
 * card action — including Blood's loot, Highway Robbery's cost-then-draw, Faithless Looting's resolution
 * discard, and Ash Barrens' search — is a real random-legal choice.
 *
 * The contract is **zero invariant violations, bounded termination**: [ScriptedGame] invariant-checks every
 * single transition and throws on any violation (PLAN.md §2.3), so a corpus that finishes proves every
 * playout stayed valid. A playout that reaches the turn/decision bound without ending is INCONCLUSIVE — a
 * tolerated stall, not a failure (random play need not converge) — and is counted and reported. At least
 * one seed must run to a real conclusion, proving the decks actually play out. Corpus-scale tuning and the
 * scripted full-game scenarios are P6.3.
 */
class MvpIgnitionAcceptanceSpec :
    StringSpec({
        "P6.2c ignition: pure random-legal Mono-Red-Madness vs GW-Bogles games run green (no gap-avoidance)" {
            val seeds = fuzzSeeds(default = IGNITION_SEEDS)
            var terminated = 0
            var inconclusive = 0
            seeds.forEach { seed ->
                val game =
                    ScriptedGame
                        .start(mvpMatchupConfig(seed))
                        .playUntilOverOrBound(RandomLegalResponder(seed), turnCap = REAL_CARD_TURN_CAP)
                if (game.isOver) terminated++ else inconclusive++
            }
            // Reported per the DoD: a stall-capped (INCONCLUSIVE) seed is tolerated; a violation would have thrown.
            println(
                "P6.2c ignition corpus: ${seeds.size} seeds — $terminated terminated, $inconclusive inconclusive " +
                    "(stall-capped at turn $REAL_CARD_TURN_CAP), 0 invariant violations.",
            )
            // A real corpus, not a no-op: at least one seed ran the two real decks to a genuine conclusion.
            terminated shouldBeGreaterThan 0
        }
    })

/** Seed count for the pure-random ignition corpus — at least sixteen (P6.2c); scaled by `-PfuzzSeeds`. */
private const val IGNITION_SEEDS: Int = 16
