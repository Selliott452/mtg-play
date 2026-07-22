package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.acceptance.fuzz.FuzzCorpus
import dev.mtgplay.acceptance.fuzz.FuzzHarness
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.event.LossReason
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.TurnStep
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

// The asymmetric mixed-matchup corpus size. A creatureless burn deck versus a creature-aggro deck
// is a genuine race, so a modest corpus already exhibits both win paths; `-PfuzzSeeds=N` scales it.
private const val MIXED_SEED_COUNT: Int = 60

// Generous per-game turn cap. This race resolves fast; a game still going at the cap is a stall.
private const val MIXED_TURN_CAP: Int = 80

// Runaway guard on decisions per game.
private const val MIXED_DECISION_CAP: Int = 50_000

/**
 * The asymmetric mixed-matchup fuzz corpus (P3.3, deliverable 5): a pure burn deck (seat [alice], no
 * creatures) versus a creature-aggro deck (seat [bob]) across a corpus of random-legal games. It is
 * the first corpus where two archetypes fight *each other* rather than a mirror.
 *
 * The fuzz contract (PLAN.md §2.3) holds as always — the [FuzzHarness] invariant-checks every
 * transition and enumeration-completeness-probes sampled windows, aborting with a persisted repro on
 * any failure, so a returned report is proof of zero violations. Beyond that, the corpus is asserted
 * to exercise *both* archetypes' win paths: some games are decided by burn (a Bolt deals the lethal
 * blow, CR 704.5a) and some by creature combat (combat damage deals the lethal blow, CR 704.5g never
 * saving the blocker-less burn player). All randomness flows through the seeded core `Rng`
 * (ADR-006).
 */
class MixedMatchupCorpusSpec :
    StringSpec({

        val seeds = fuzzSeeds(MIXED_SEED_COUNT)
        "${seeds.size} seeds of burn vs creature-aggro: no violations, and both archetype win paths occur" {
            var burnKills = 0
            var combatKills = 0
            var otherKills = 0

            val report =
                FuzzHarness.run(
                    FuzzCorpus(
                        name = "mixed-burn-vs-creatures",
                        seeds = seeds,
                        configForSeed = { seed -> mixedMatchupConfig(seed) },
                        caps = FuzzCorpus.Caps(turnCap = MIXED_TURN_CAP, decisionCap = MIXED_DECISION_CAP),
                    ),
                ) { game, outcome ->
                    val result = outcome.result
                    if (result != null && result.reason == LossReason.LIFE_TOTAL_ZERO_OR_LESS) {
                        when (lethalBlow(game, result.loser)) {
                            LethalBlow.BOLT -> burnKills += 1
                            LethalBlow.COMBAT -> combatKills += 1
                            LethalBlow.UNKNOWN -> otherKills += 1
                        }
                    }
                }

            // A concise stats line for the packet report / CI log (deliverable 5: report stats).
            println(
                "MIXED CORPUS ${report.summary()}; win paths: burn=$burnKills combat=$combatKills " +
                    "other=$otherKills, creatureDeaths=${report.creatureDeaths}, fizzles=${report.fizzles}",
            )

            // Both archetype win paths must actually occur across the corpus.
            burnKills shouldBeGreaterThan 0
            combatKills shouldBeGreaterThan 0
            // Every life-loss game was attributed to one of the two damage sources (no UNKNOWN).
            otherKills shouldBe 0
            // Every seed is accounted for as decisive or a stall (the harness guarantees no failure).
            (report.decisive + report.inconclusive) shouldBe report.seedCount
        }
    })

/** How the losing player took their lethal blow. */
private enum class LethalBlow {
    /** The final point of damage came from a resolving Lightning Bolt (CR 704.5a burn kill). */
    BOLT,

    /** The final point of damage came from a combat-damage step (CR 704.5g creature kill). */
    COMBAT,

    /** No damage to the loser was found (should not happen for a life-loss ending). */
    UNKNOWN,
}

/**
 * Classifies the killing blow to [loser] in [game]'s event log. Because a life total reaching 0 or
 * less ends the game immediately as a state-based action (CR 704.5a), the *last* damage dealt to the
 * loser is the lethal one; its cause is whatever most recently set the damage context — a
 * combat-damage step ([TurnStep.COMBAT_DAMAGE]) for combat, a resolving Lightning Bolt for burn.
 */
private fun lethalBlow(
    game: ScriptedGame,
    loser: PlayerId,
): LethalBlow {
    var contextIsBolt = false
    var lastBlow = LethalBlow.UNKNOWN
    for (event in game.state.events) {
        contextIsBolt = updatedDamageContext(event, contextIsBolt)
        if (event is GameEvent.DamageDealt && event.recipient == Target.Player(loser)) {
            lastBlow = if (contextIsBolt) LethalBlow.BOLT else LethalBlow.COMBAT
        }
    }
    return lastBlow
}

/**
 * The damage context after [event]: a combat-damage step ([TurnStep.COMBAT_DAMAGE]) sets combat, a
 * resolving Lightning Bolt sets Bolt, and any other event leaves the [current] context unchanged.
 */
private fun updatedDamageContext(
    event: GameEvent,
    current: Boolean,
): Boolean =
    when (event) {
        is GameEvent.StepBegan -> if (event.step == TurnStep.COMBAT_DAMAGE) false else current
        is GameEvent.SpellResolved -> if (event.card == CardRef("Lightning Bolt")) true else current
        else -> current
    }
