package dev.mtgplay.acceptance.fuzz

import dev.mtgplay.acceptance.driver.LANDS_ONLY_TURN_CAP
import dev.mtgplay.acceptance.mountainConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/**
 * The unified fuzz driver (P3.3, deliverable 1): that it plays a whole corpus, classifies each seed,
 * runs the enumeration-completeness probe, and drives the per-seed inspector — on the real engine,
 * where a returned report is itself proof that every transition was invariant-clean (PLAN.md §2.3).
 */
class FuzzHarnessSpec :
    StringSpec({

        val seeds = (0L until 8L).toList()

        "a lands-only corpus classifies every seed a deck-out, probes options, and runs the inspector" {
            var inspected = 0
            val report =
                FuzzHarness.run(
                    FuzzCorpus(
                        name = "lands-only-small",
                        seeds = seeds,
                        configForSeed = { seed -> mountainConfig(seed = seed, startingPlayer = null) },
                        caps = FuzzCorpus.Caps(turnCap = LANDS_ONLY_TURN_CAP),
                    ),
                ) { _, _ -> inspected += 1 }

            report.seedCount shouldBe seeds.size
            // Lands-only decks cannot lose life, so every game decks out within the cap (CR 104.3c).
            report.deckOuts shouldBe seeds.size
            report.decisive shouldBe seeds.size
            report.inconclusive shouldBe 0
            // The completeness probe was active across the corpus (deliverable 2).
            report.probedWindows shouldBeGreaterThan 0
            report.probedOptions shouldBeGreaterThan 0
            // The inspector ran exactly once per seed.
            inspected shouldBe seeds.size
        }

        "a game that outruns the turn cap is classified inconclusive, not a failure" {
            val report =
                FuzzHarness.run(
                    FuzzCorpus(
                        name = "lands-only-capped",
                        seeds = seeds,
                        configForSeed = { seed -> mountainConfig(seed = seed, startingPlayer = null) },
                        // A cap far below the ~108-turn deck-out: every game is a stall.
                        caps = FuzzCorpus.Caps(turnCap = 3),
                    ),
                )

            report.inconclusive shouldBe seeds.size
            report.decisive shouldBe 0
            report.seedOutcomes.forEach { it.outcome shouldBe Outcome.INCONCLUSIVE }
            report.seedOutcomes.forEach { it.result shouldBe null }
        }
    })
