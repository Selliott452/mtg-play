package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.LANDS_ONLY_TURN_CAP
import dev.mtgplay.acceptance.fuzz.FuzzCorpus
import dev.mtgplay.acceptance.fuzz.FuzzHarness
import dev.mtgplay.core.event.LossReason
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

// The lands-only smoke corpus size. Raise it freely — nothing else changes — for a heavier local
// run; CI keeps it small so every push stays fast. `-PfuzzSeeds=N` overrides it (P3.3 scaling knob).
private const val SEED_COUNT: Int = 64

// The real-card burn corpus (P2.2; raised 50 -> 200 in P2.3 — burn games end fast, so the whole
// corpus costs ~3s): 20 Mountains + 40 Lightning Bolts per seat forces constant action, so bolt
// deaths (CR 704.5a) dominate these endings.
private const val BURN_SEED_COUNT: Int = 200

// The sublethal-corpus size (raised 12 -> 24 in P2.3): 2 Bolts per seat cannot reach 20 life (see
// SUBLETHAL_BOLT_COUNT), so every one of these games must end as a deck-out (CR 704.5c) — which is
// how the corpus as a whole is guaranteed to exhibit both ending kinds.
private const val SUBLETHAL_SEED_COUNT: Int = 24

/**
 * The randomized-playout smoke: full games on real cards ([dev.mtgplay.cards.MvpCards]) driven by
 * uniformly random *legal* decisions (ADR-005) all the way to completion, now run through the
 * unified [FuzzHarness] (P3.3). The properties asserted are the fuzz harness' core contract
 * (PLAN.md §2.3): no exceptions, no invariant violations (the harness checks every transition and
 * aborts with a persisted repro on any), bounded termination — and, across the P2.2 corpus, both
 * ending kinds actually occur: bolt deaths (CR 704.5a) and deck-outs (CR 704.5c). Every window is
 * additionally enumeration-completeness-probed (ADR-005, deliverable 2). All randomness flows
 * through the seeded core `Rng` (ADR-006).
 */
class RandomizedSmokeSpec :
    StringSpec({

        val landsSeeds = fuzzSeeds(SEED_COUNT)
        "${landsSeeds.size} seeds of random-legal lands-only games complete as deck-outs, within the turn cap" {
            val report =
                FuzzHarness.run(
                    FuzzCorpus(
                        name = "lands-only",
                        seeds = landsSeeds,
                        configForSeed = { seed -> mountainConfig(seed = seed, startingPlayer = null) },
                        caps = FuzzCorpus.Caps(turnCap = LANDS_ONLY_TURN_CAP),
                    ),
                )

            // Lands-only decks can never lose life, so every game must deck out (CR 104.3c) inside
            // the cap; the harness already invariant-checked every transition of every seed.
            report.deckOuts shouldBe report.seedCount
            report.lifeLosses shouldBe 0
            report.inconclusive shouldBe 0
            report.seedOutcomes.forEach { it.finalTurnNumber shouldBeLessThanOrEqual LANDS_ONLY_TURN_CAP }
        }

        val burnSeeds = fuzzSeeds(BURN_SEED_COUNT)
        "${burnSeeds.size} seeds of random-legal burn games — lands, casts, targets, payments — end cleanly" {
            val report =
                FuzzHarness.run(
                    FuzzCorpus(
                        name = "burn",
                        seeds = burnSeeds,
                        configForSeed = { seed -> burnConfig(seed) },
                        caps = FuzzCorpus.Caps(turnCap = REAL_CARD_TURN_CAP),
                    ),
                ) { game, outcome ->
                    // Per-seat SBA honesty on top of the harness' classification: a life-loss loser
                    // is actually at 0 or less (CR 704.5a); a deck-out loser's library is actually
                    // empty (CR 704.5c).
                    outcome.result?.let { result ->
                        when (result.reason) {
                            LossReason.LIFE_TOTAL_ZERO_OR_LESS ->
                                game.state.players
                                    .getValue(result.loser)
                                    .life shouldBeLessThanOrEqual 0
                            LossReason.ATTEMPTED_DRAW_FROM_EMPTY_LIBRARY ->
                                game.state.players
                                    .getValue(result.loser)
                                    .library
                                    .shouldBeEmpty()
                        }
                    }
                }

            // Every burn game resolves within the cap, and bolts fly: bolt deaths must occur.
            report.decisive shouldBe report.seedCount
            report.lifeLosses shouldBeGreaterThan 0
        }

        val sublethalSeeds = fuzzSeeds(SUBLETHAL_SEED_COUNT)
        "${sublethalSeeds.size} seeds of sublethal-bolt games always deck out — the corpus exhibits both endings" {
            val report =
                FuzzHarness.run(
                    FuzzCorpus(
                        name = "sublethal-bolt",
                        seeds = sublethalSeeds,
                        configForSeed = { seed -> burnConfig(seed, bolts = SUBLETHAL_BOLT_COUNT) },
                        caps = FuzzCorpus.Caps(turnCap = REAL_CARD_TURN_CAP),
                    ),
                ) { game, outcome ->
                    // Total possible Bolt damage (4 Bolts x 3) cannot reach 20 starting life, so the
                    // only reachable ending is the CR 704.5c deck-out: the loser's library is empty.
                    outcome.result?.let { result ->
                        game.state.players
                            .getValue(result.loser)
                            .library
                            .shouldBeEmpty()
                    }
                }

            report.deckOuts shouldBe report.seedCount
            report.lifeLosses shouldBe 0
        }
    })
