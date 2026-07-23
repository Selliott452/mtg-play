package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.fuzz.FuzzCorpus
import dev.mtgplay.acceptance.fuzz.FuzzHarness
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

// The P4.2 aura corpus size. The packet requires >= 50 seeds; kept modest so `./gradlew build` stays
// fast, and `-PfuzzSeeds=N` scales it for nightly CI (P3.3 knob).
private const val AURA_SEED_COUNT: Int = 80

// Generous per-game turn cap. Aura boards can grind; a game still going at the cap is a board stall,
// counted INCONCLUSIVE (the CR imposes no turn limit — a stall is not a failure).
private const val AURA_TURN_CAP: Int = 80

// Runaway guard on decisions per game (far above any real aura game's count).
private const val AURA_DECISION_CAP: Int = 60_000

/**
 * The P4.2 fuzz corpus with the real Bogles Auras (deliverable 4): full games on symmetric
 * [boglesAuraDeck]s — Grizzly Bears, all seven Auras, Lightning Bolts, and a three-colour mana base —
 * driven by uniformly random *legal* decisions (ADR-005) through the unified [FuzzHarness] (P3.3).
 *
 * The fuzz contract (PLAN.md §2.3) holds throughout: the harness invariant-checks **every**
 * transition — including the new [dev.mtgplay.acceptance.invariant.Invariant.ATTACHMENT_INTEGRITY]
 * check and the layered-toughness lethality classification — and enumeration-completeness-probes
 * sampled windows, aborting with a persisted repro on any failure, so a returned report is itself
 * proof of zero violations. Beyond that, the corpus is asserted to actually exercise the packet's new
 * rules across the seeds: Auras get **cast and attached** ([GameEvent.AuraAttached], CR 303.4f), and
 * the **CR 704.5m aura fall-off** fires ([GameEvent.AuraFellOff]) as enchanted creatures die
 * (CR 700.4) — the attachment churn the invariant guards. All randomness flows through the seeded
 * core `Rng` (ADR-006).
 */
class BoglesAuraCorpusSpec :
    StringSpec({

        val seeds = fuzzSeeds(AURA_SEED_COUNT)
        "${seeds.size} seeds of random-legal aura games: no violations, Auras cast, CR 704.5m fall-off, and triggers" {
            var aurasAttached = 0
            var aurasFellOff = 0
            var triggersPlaced = 0
            var tokensCreated = 0
            var rancorsReturned = 0
            var lifeGains = 0

            val report =
                FuzzHarness.run(
                    FuzzCorpus(
                        name = "bogles-auras",
                        seeds = seeds,
                        configForSeed = { seed -> boglesAuraConfig(seed) },
                        caps = FuzzCorpus.Caps(turnCap = AURA_TURN_CAP, decisionCap = AURA_DECISION_CAP),
                    ),
                ) { game, _ ->
                    // Aura- and trigger-specific facts from the generic event log (the harness stays
                    // card-agnostic). The trigger framework (P5.1) is now exercised across the corpus.
                    val events = game.state.events
                    aurasAttached += events.count { it is GameEvent.AuraAttached }
                    aurasFellOff += events.count { it is GameEvent.AuraFellOff }
                    triggersPlaced += events.count { it is GameEvent.TriggeredAbilityPutOnStack }
                    tokensCreated += events.count { it is GameEvent.TokenCreated }
                    rancorsReturned +=
                        events.count { it is GameEvent.CardReturnedToHand && it.card == CardRef("Rancor") }
                    lifeGains += events.count { it is GameEvent.LifeChanged && it.change > 0 }
                }

            // A concise stats line for the packet report / CI log (deliverable 9: report stats).
            println(
                "AURA CORPUS ${report.summary()}; aurasAttached=$aurasAttached aurasFellOff=$aurasFellOff, " +
                    "triggersPlaced=$triggersPlaced tokensCreated=$tokensCreated rancorsReturned=$rancorsReturned " +
                    "lifeGains=$lifeGains, creatureDeaths=${report.creatureDeaths}, fizzles=${report.fizzles}",
            )

            // Auras were cast and attached across the corpus (CR 303.4f).
            aurasAttached shouldBeGreaterThan 0
            // The CR 704.5m fall-off fired: enchanted creatures died and their Auras were torn off.
            aurasFellOff shouldBeGreaterThan 0
            // The trigger framework fired end-to-end: abilities were put on the stack (CR 603.3b),
            // tokens were created (Cartouche, CR 111.4), and Rancor returned to hand (CR 603.6b).
            triggersPlaced shouldBeGreaterThan 0
            tokensCreated shouldBeGreaterThan 0
            rancorsReturned shouldBeGreaterThan 0
            // Every seed is accounted for as decisive or a stall (the harness guarantees no failure).
            (report.decisive + report.inconclusive) shouldBe report.seedCount
        }
    })
