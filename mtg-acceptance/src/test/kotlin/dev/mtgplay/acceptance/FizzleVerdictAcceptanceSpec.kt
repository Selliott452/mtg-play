package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.RandomLegalResponder
import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.event.LossReason
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull

// The random corpus the no-fizzle scan sweeps; sized to keep the suite fast while reliably
// producing CR 704.5a deaths (the endings a fizzle would have to hide behind).
private const val FIZZLE_SCAN_SEED_COUNT: Int = 12

/**
 * The P2.3 fizzle verdict (CR 608.2b): **a fizzle is unreachable end-to-end in P2.x**.
 *
 * Proof sketch:
 * 1. A spell fizzles only when *every* target is illegal as it would resolve (CR 608.2b). The
 *    only targets the P2.x pool can produce are players — `TargetSpec.AnyTarget` enumerates
 *    the seated players and nothing else — and a player target is illegal exactly when the
 *    player is not in that enumeration (mtg-rules `Targets.kt`); nothing in the pool grants
 *    protection, hexproof, or any other targeting restriction.
 * 2. A seated player only stops being a legal target by leaving the game, i.e. losing
 *    (CR 104.3). In a two-player game the loss *is* the end of the game (CR 104.2a): the
 *    engine performs both in the same state-based-action transition.
 * 3. State-based actions are performed whenever a player would receive priority (CR 704.3) —
 *    in particular before the priority round that precedes any resolution (CR 117.3b, CR
 *    117.4) — and a finished game never advances again (ADR-004: `GameOver` is terminal). So
 *    no resolution can ever begin after a player has left: the game that would have produced
 *    the fizzle has already ended, its would-fizzle spell stranded unresolved on the stack.
 *
 * Hence no reachable two-player P2.x game emits `SpellFizzled`. The fizzle branch itself is
 * pinned at unit level (P2.1) in mtg-rules' `StackResolutionSpec` ("CR 608.2b: a spell whose
 * only target is illegal on resolution fizzles to the graveyard"), which drives
 * `resolveTopOfStack` directly with a handcrafted entry targeting an unseated player. The
 * suite below asserts the two *reachable* neighbours of the fizzle: the game-ends-first path
 * with the spell stranded (see also `DeathMidStackAcceptanceSpec`), and corpus-wide absence
 * of `SpellFizzled`.
 */
class FizzleVerdictAcceptanceSpec :
    StringSpec({

        "CR 608.2b vs CR 704.5a: the spell whose target died is stranded on the stack unresolved, not fizzled" {
            val outcome = deathMidStackDuel(alice)
            val events = outcome.game.state.events
            // The doomed Bolt — its controller dead, its target alive — neither resolved nor
            // fizzled: the game ended before its resolution was ever attempted.
            events.filterIsInstance<GameEvent.SpellFizzled>().shouldBeEmpty()
            events
                .filterIsInstance<GameEvent.SpellResolved>()
                .filter { it.objectId == outcome.initiatorBoltId }
                .shouldBeEmpty()
            outcome.game.state.sharedZones.stack shouldHaveSize 1
        }

        "CR 608.2b: no SpellFizzled across a $FIZZLE_SCAN_SEED_COUNT-seed random real-card corpus" {
            var boltDeaths = 0
            (0 until FIZZLE_SCAN_SEED_COUNT).forEach { seed ->
                val game =
                    ScriptedGame
                        .start(burnConfig(seed.toLong()))
                        .playToCompletion(RandomLegalResponder(seed.toLong()), turnCap = REAL_CARD_TURN_CAP)
                val result = game.result.shouldNotBeNull()
                if (result.reason == LossReason.LIFE_TOTAL_ZERO_OR_LESS) boltDeaths += 1
                game.state.events
                    .filterIsInstance<GameEvent.SpellFizzled>()
                    .shouldBeEmpty()
            }
            // The scan is meaningful: deaths — the endings a fizzle would have to hide behind —
            // actually occurred, and still nothing fizzled.
            boltDeaths shouldBeGreaterThan 0
        }
    })
