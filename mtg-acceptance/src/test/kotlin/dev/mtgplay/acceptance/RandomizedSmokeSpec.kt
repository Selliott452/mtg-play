package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.LANDS_ONLY_TURN_CAP
import dev.mtgplay.acceptance.driver.RandomLegalResponder
import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.acceptance.invariant.InvariantChecker
import dev.mtgplay.core.event.LossReason
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

// The lands-only smoke corpus size. Raise it freely — nothing else changes — for a heavier
// local run; CI keeps it small so every push stays fast.
private const val SEED_COUNT: Int = 64

// The real-card burn corpus (P2.2; raised 50 -> 200 in P2.3 — burn games end fast, so the
// whole corpus costs ~3s): 20 Mountains + 40 Lightning Bolts per seat forces constant action,
// so bolt deaths (CR 704.5a) dominate these endings.
private const val BURN_SEED_COUNT: Int = 200

// The sublethal-corpus size (raised 12 -> 24 in P2.3): 2 Bolts per seat cannot reach 20 life
// (see SUBLETHAL_BOLT_COUNT), so every one of these games must end as a deck-out (CR 704.5c) —
// which is how the corpus as a whole is guaranteed to exhibit both ending kinds.
private const val SUBLETHAL_SEED_COUNT: Int = 24

/**
 * The randomized-playout smoke: full games on real cards ([dev.mtgplay.cards.MvpCards]) driven
 * by uniformly random *legal* decisions (ADR-005) all the way to completion. The properties
 * asserted are the fuzz harness' core contract (PLAN.md §2.3): no exceptions, no invariant
 * violations (the driver checks every transition), bounded termination — and, across the
 * P2.2 corpus, both ending kinds actually occur: bolt deaths (CR 704.5a) and deck-outs
 * (CR 704.5c). All randomness flows through the seeded core `Rng` (ADR-006).
 */
class RandomizedSmokeSpec :
    StringSpec({

        "$SEED_COUNT seeds of random-legal lands-only games complete with no violations, within the turn cap" {
            (0 until SEED_COUNT).forEach { seed ->
                val game =
                    ScriptedGame
                        .start(mountainConfig(seed = seed.toLong(), startingPlayer = null))
                        .playToCompletion(RandomLegalResponder(seed.toLong()), turnCap = LANDS_ONLY_TURN_CAP)

                // Terminated as a deck-out (CR 104.3c), within the cap, and invariant-clean —
                // now with real Mountains being played and the land drop exercised throughout.
                game.result?.reason shouldBe LossReason.ATTEMPTED_DRAW_FROM_EMPTY_LIBRARY
                game.state.turn.number shouldBeLessThanOrEqual LANDS_ONLY_TURN_CAP
                InvariantChecker.check(game.state, game.cardBaseline).shouldBeEmpty()
            }
        }

        "$BURN_SEED_COUNT seeds of random-legal burn games — lands, casts, targets, payments — end cleanly" {
            var boltDeaths = 0
            (0 until BURN_SEED_COUNT).forEach { seed ->
                val game =
                    ScriptedGame
                        .start(burnConfig(seed.toLong()))
                        .playToCompletion(RandomLegalResponder(seed.toLong()), turnCap = REAL_CARD_TURN_CAP)

                val result = checkNotNull(game.result) { "playToCompletion returned without a result" }
                game.state.turn.number shouldBeLessThanOrEqual REAL_CARD_TURN_CAP
                InvariantChecker.check(game.state, game.cardBaseline).shouldBeEmpty()
                when (result.reason) {
                    // CR 704.5a: the loser's life total must actually be 0 or less.
                    LossReason.LIFE_TOTAL_ZERO_OR_LESS -> {
                        boltDeaths += 1
                        game.state.players
                            .getValue(result.loser)
                            .life shouldBeLessThanOrEqual 0
                    }
                    // CR 704.5c: random players may also burn nobody out and deck out instead.
                    LossReason.ATTEMPTED_DRAW_FROM_EMPTY_LIBRARY ->
                        game.state.players
                            .getValue(result.loser)
                            .library
                            .shouldBeEmpty()
                }
            }
            // Bolts fly in a random burn corpus: bolt deaths must actually occur (CR 704.5a).
            boltDeaths shouldBeGreaterThan 0
        }

        "$SUBLETHAL_SEED_COUNT seeds of sublethal-bolt games always deck out — the corpus exhibits both endings" {
            (0 until SUBLETHAL_SEED_COUNT).forEach { seed ->
                val game =
                    ScriptedGame
                        .start(burnConfig(seed.toLong(), bolts = SUBLETHAL_BOLT_COUNT))
                        .playToCompletion(RandomLegalResponder(seed.toLong()), turnCap = REAL_CARD_TURN_CAP)

                val result = checkNotNull(game.result) { "playToCompletion returned without a result" }
                // Total possible Bolt damage (4 Bolts x 3) cannot reach 20 starting life, so the
                // only reachable ending is the CR 704.5c deck-out.
                result.reason shouldBe LossReason.ATTEMPTED_DRAW_FROM_EMPTY_LIBRARY
                game.state.players
                    .getValue(result.loser)
                    .library
                    .shouldBeEmpty()
                InvariantChecker.check(game.state, game.cardBaseline).shouldBeEmpty()
            }
        }
    })
