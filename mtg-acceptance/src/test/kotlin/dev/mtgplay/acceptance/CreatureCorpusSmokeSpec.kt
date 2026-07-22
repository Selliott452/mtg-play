package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.acceptance.fuzz.FuzzCorpus
import dev.mtgplay.acceptance.fuzz.FuzzHarness
import dev.mtgplay.acceptance.fuzz.Outcome
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.TurnStep
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

// The creature-combat corpus size. Raise it freely for a heavier local run; CI keeps it modest so
// every push stays fast (`-PfuzzSeeds=N` overrides it, P3.3). Sized (with the deck mix, see
// AcceptanceTestSupport.creatureDeck) so the three P3.2 death paths — combat trade, Bolt kill, and
// fizzle — all appear corpus-wide.
private const val CREATURE_SEED_COUNT: Int = 200

// Generous per-game turn cap. Aggressive creature/Bolt games end well inside it; a game still going
// at the cap is a board stall, counted INCONCLUSIVE (CR has no turn limit — it is not a failure).
private const val CREATURE_TURN_CAP: Int = 80

// Runaway guard on decisions per game (far above any real creature game's count).
private const val CREATURE_DECISION_CAP: Int = 50_000

/**
 * The P3.2 fuzz smoke on real creatures: full games on [dev.mtgplay.cards.MvpCards] creature-combat
 * decks driven by uniformly random *legal* decisions (ADR-005), now run through the unified
 * [FuzzHarness] (P3.3) — the random corpus fighting real combats. The fuzz contract (PLAN.md §2.3)
 * holds throughout: no exceptions and no invariant violations (the harness checks every transition
 * and aborts with a persisted repro on any), enumeration-completeness probing at every sampled
 * window (deliverable 2), and bounded termination. Board stalls that reach the turn cap are
 * inconclusive, not failures.
 *
 * Beyond "nothing crashed", the corpus is asserted to actually exercise the packet's new rules:
 * across the seeds, creatures die **by combat** (CR 704.5g after a combat-damage step) and **by
 * Bolt** (CR 704.5g after a Lightning Bolt resolves), and at least one Bolt **fizzles** (CR 608.2b,
 * its creature target killed out from under it). All randomness flows through the seeded core `Rng`
 * (ADR-006).
 */
class CreatureCorpusSmokeSpec :
    StringSpec({

        val seeds = fuzzSeeds(CREATURE_SEED_COUNT)
        "${seeds.size} seeds of random-legal creature games: no violations, and all three death paths occur" {
            var combatDeaths = 0
            var boltDeaths = 0

            val report =
                FuzzHarness.run(
                    FuzzCorpus(
                        name = "creatures",
                        seeds = seeds,
                        configForSeed = { seed -> creatureConfig(seed) },
                        caps = FuzzCorpus.Caps(turnCap = CREATURE_TURN_CAP, decisionCap = CREATURE_DECISION_CAP),
                    ),
                ) { game, outcome ->
                    // Card-specific split of the harness' generic creature-death count into combat
                    // versus Bolt kills, on the decisive games (a stall's board is mid-fight).
                    if (outcome.outcome != Outcome.INCONCLUSIVE) {
                        val (combat, bolt) = classifyCreatureDeaths(game)
                        combatDeaths += combat
                        boltDeaths += bolt
                    }
                }

            // Corpus-wide, every new P3.2 death path fired at least once. The observed margins are
            // wide (see the packet report), so "at least one" is comfortable, not knife-edge.
            report.decisive shouldBeGreaterThan 0
            combatDeaths shouldBeGreaterThan 0
            boltDeaths shouldBeGreaterThan 0
            report.fizzles shouldBeGreaterThan 0
            // Every seed is accounted for: it either resolved or was flagged a board stall.
            (report.decisive + report.inconclusive) shouldBe report.seedCount
        }
    })

/**
 * Classifies the creature deaths in [game]'s event log as (combat, bolt) counts. A death is
 * attributed to whatever most recently set the "damage context": a combat-damage step
 * ([GameEvent.StepBegan] with [TurnStep.COMBAT_DAMAGE]) marks combat, a resolving Lightning Bolt
 * ([GameEvent.SpellResolved]) marks Bolt — and a [GameEvent.CreatureDied] fires immediately after
 * whichever dealt its lethal blow, so the most recent context is its cause. Fizzles never reach
 * this (a fizzled Bolt deals no damage and emits no [GameEvent.SpellResolved]).
 */
private fun classifyCreatureDeaths(game: ScriptedGame): Pair<Int, Int> {
    var combat = 0
    var bolt = 0
    var contextIsBolt = false
    for (event in game.state.events) {
        when (event) {
            is GameEvent.StepBegan -> if (event.step == TurnStep.COMBAT_DAMAGE) contextIsBolt = false
            is GameEvent.SpellResolved -> if (event.card == CardRef("Lightning Bolt")) contextIsBolt = true
            is GameEvent.CreatureDied -> if (contextIsBolt) bolt += 1 else combat += 1
            else -> Unit
        }
    }
    return combat to bolt
}
