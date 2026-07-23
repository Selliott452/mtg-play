package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.RandomLegalResponder
import dev.mtgplay.acceptance.driver.Responder
import dev.mtgplay.acceptance.fuzz.FuzzCorpus
import dev.mtgplay.acceptance.fuzz.FuzzHarness
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.layeredCharacteristics
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

// The P5.3 keyword corpus size. Kept modest so `./gradlew build` stays fast; `-PfuzzSeeds=N` scales
// it for nightly CI (P3.3 knob). Large enough that random-legal play reliably produces both a
// blocked Rancor'd attacker (a trample assignment) and an opponent holding a hexproof creature.
private const val KEYWORD_SEED_COUNT: Int = 120

// Generous per-game turn cap. Aura/creature boards can grind; a stall at the cap is INCONCLUSIVE.
private const val KEYWORD_TURN_CAP: Int = 80

// Runaway guard on decisions per game.
private const val KEYWORD_DECISION_CAP: Int = 60_000

/**
 * The P5.3 keyword fuzz corpus (deliverable 5): full games on symmetric [boglesKeywordDeck]s — the
 * three real hexproof one-drops, Grizzly Bears, a Rancor trample package, and Lightning Bolts —
 * driven by uniformly random *legal* decisions (ADR-005) through the [FuzzHarness].
 *
 * Beyond the standing fuzz contract (every transition invariant-checked, sampled windows
 * enumeration-probed — a returned report is proof of zero violations), a tracking responder asserts
 * the two P5.3 targeting/combat properties across the whole corpus:
 * - **Hexproof exclusion (CR 702.11):** no target enumeration surfaced by a seat ever contains an
 *   *opponent's* hexproof creature — Bolts and Rancors route around them — while such creatures were
 *   in fact present during targeting, so the guarantee is non-vacuous.
 * - **Trample assignments (CR 702.19e):** the [DecisionRequest.AssignTrampleDamage] decision actually
 *   occurs — a Rancor'd attacker gets blocked with above-lethal excess and its controller assigns it.
 */
class BoglesKeywordCorpusSpec :
    StringSpec({

        val seeds = fuzzSeeds(KEYWORD_SEED_COUNT)
        "${seeds.size} seeds of random-legal keyword games: no violations, hexproof routed around, trample assigned" {
            val tracker = KeywordTracker()

            val report =
                FuzzHarness.run(
                    FuzzCorpus(
                        name = "bogles-keywords",
                        seeds = seeds,
                        configForSeed = { seed -> boglesKeywordConfig(seed) },
                        caps = FuzzCorpus.Caps(turnCap = KEYWORD_TURN_CAP, decisionCap = KEYWORD_DECISION_CAP),
                        responderForSeed = { seed -> tracker.responderFor(seed) },
                    ),
                )

            println(
                "KEYWORD CORPUS ${report.summary()}; trampleAssignments=${tracker.trampleAssignments} " +
                    "hexproofPresentAtTargeting=${tracker.hexproofPresentAtTargeting} " +
                    "hexproofInOptions=${tracker.hexproofInOptions}, creatureDeaths=${report.creatureDeaths}, " +
                    "fizzles=${report.fizzles}",
            )

            // CR 702.11: an opponent's hexproof creature was never enumerated as a legal target…
            tracker.hexproofInOptions shouldBe 0
            // …and the exclusion was actually exercised — opponents held hexproof creatures during targeting.
            tracker.hexproofPresentAtTargeting shouldBeGreaterThan 0
            // CR 702.19e: real trample-assignment decisions occurred across the corpus.
            tracker.trampleAssignments shouldBeGreaterThan 0
            // Every seed is accounted for as decisive or a stall (the harness guarantees no failure).
            (report.decisive + report.inconclusive) shouldBe report.seedCount
        }
    })

/**
 * Corpus-wide tallies gathered by a responder that wraps [RandomLegalResponder]: how many trample
 * assignments were surfaced, how often a targeting decision saw an opponent hexproof creature, and —
 * the bug tripwire — how often such a creature nonetheless appeared among the enumerated options
 * (must stay zero). The wrapped responder still plays uniformly at random, so it does not perturb the
 * corpus (ADR-006).
 */
private class KeywordTracker {
    var trampleAssignments: Int = 0
    var hexproofPresentAtTargeting: Int = 0
    var hexproofInOptions: Int = 0

    fun responderFor(seed: Long): Responder {
        val delegate = RandomLegalResponder(seed)
        return Responder { request, state ->
            observe(request, state)
            delegate.respond(request, state)
        }
    }

    private fun observe(
        request: DecisionRequest,
        state: GameState,
    ) {
        when (request) {
            is DecisionRequest.AssignTrampleDamage -> trampleAssignments += 1
            is DecisionRequest.ChooseTargets -> observeTargeting(request, state)
            else -> Unit
        }
    }

    private fun observeTargeting(
        request: DecisionRequest.ChooseTargets,
        state: GameState,
    ) {
        val opponents =
            state.players.keys
                .filter { it != request.seat }
                .toSet()
        val opponentHexproof =
            state.sharedZones.battlefield.filter { it.owner in opponents && isHexproof(state, it.id) }
        if (opponentHexproof.isNotEmpty()) hexproofPresentAtTargeting += 1
        hexproofInOptions +=
            request.options
                .filterIsInstance<Target.Permanent>()
                .count { option ->
                    val obj = state.sharedZones.battlefield.firstOrNull { it.id == option.id }
                    obj != null && obj.owner in opponents && isHexproof(state, obj.id)
                }
    }
}

// Whether the battlefield object [id] is hexproof right now (CR 702.11, effective keywords through
// the CR 613 layer system — so an aura-granted hexproof counts too).
private fun isHexproof(
    state: GameState,
    id: ObjectId,
): Boolean = Keyword.HEXPROOF in layeredCharacteristics(state, id).keywords
