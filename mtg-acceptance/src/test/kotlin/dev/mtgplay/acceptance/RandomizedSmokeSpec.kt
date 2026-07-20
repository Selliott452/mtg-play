package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.LANDS_ONLY_TURN_CAP
import dev.mtgplay.acceptance.driver.RandomLegalResponder
import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.acceptance.invariant.InvariantChecker
import dev.mtgplay.core.event.LossReason
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

// The smoke corpus size. Raise it freely — nothing else changes — for a heavier local run; CI
// keeps it small so every push stays fast. This is the seed the Phase 3 fuzz harness grows from.
private const val SEED_COUNT: Int = 64

/**
 * The randomized-playout smoke: many lands-only games driven by uniformly random *legal* decisions
 * (ADR-005) all the way to completion. The properties asserted are the fuzz harness' core contract
 * (PLAN.md §2.3): no exceptions, no invariant violations (the driver checks every transition), and
 * bounded termination. All randomness flows through the seeded core `Rng` (ADR-006).
 */
class RandomizedSmokeSpec :
    StringSpec({

        "$SEED_COUNT seeds of random-legal lands-only games complete with no violations, within the turn cap" {
            (0 until SEED_COUNT).forEach { seed ->
                val game =
                    ScriptedGame
                        .start(mountainConfig(seed = seed.toLong(), startingPlayer = null))
                        .playToCompletion(RandomLegalResponder(seed.toLong()), turnCap = LANDS_ONLY_TURN_CAP)

                // Terminated as a deck-out (CR 104.3c), within the cap, and invariant-clean.
                game.result?.reason shouldBe LossReason.ATTEMPTED_DRAW_FROM_EMPTY_LIBRARY
                game.state.turn.number shouldBeLessThanOrEqual LANDS_ONLY_TURN_CAP
                InvariantChecker.check(game.state, game.cardBaseline).shouldBeEmpty()
            }
        }
    })
