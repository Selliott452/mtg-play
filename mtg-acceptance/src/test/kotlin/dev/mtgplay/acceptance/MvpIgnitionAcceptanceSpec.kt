package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan

/**
 * The P6.2b first-real-deck ignition check (deliverable 4): a real Mono-Red-Madness-vs-GW-Bogles game —
 * both decks now fully defined, mulligans on, random-legal responders — starts, runs, and terminates with
 * **zero invariant violations**. [ScriptedGame] invariant-checks every single transition and throws on
 * any violation, so a game that reaches `GameOver` proves the whole playout stayed valid (PLAN.md §2.3).
 *
 * This is the ignition check, not the corpus: a handful of seeds proving the two real decks actually run
 * against each other. Corpus-scale tuning and the full-game scripted scenarios are P6.3. The responder
 * routes around the three STOP-flagged card actions whose resolution needs an unbuilt engine mechanism
 * (see [GapAvoidingResponder] and the P6.2b report); every other real card is exercised in real games.
 */
class MvpIgnitionAcceptanceSpec :
    StringSpec({
        "P6.2b ignition: real Mono-Red-Madness vs GW-Bogles games run green and terminate" {
            fuzzSeeds(default = IGNITION_SEEDS).forEach { seed ->
                val game =
                    ScriptedGame
                        .start(mvpMatchupConfig(seed))
                        .playToCompletion(GapAvoidingResponder(seed), turnCap = REAL_CARD_TURN_CAP)
                withClue("seed $seed did not run green to termination") {
                    game.isOver.shouldBeTrue()
                    // A real game, not a no-op: it advanced well past the opening turn.
                    game.state.turn.number shouldBeGreaterThan 1
                }
            }
        }
    })

/** Seed count for the ignition check — a handful; the full corpus is P6.3. Scaled by `-PfuzzSeeds`. */
private const val IGNITION_SEEDS: Int = 4
