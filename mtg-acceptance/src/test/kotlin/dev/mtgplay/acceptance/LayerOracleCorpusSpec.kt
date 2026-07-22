package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.RandomLegalResponder
import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.rules.engine.layeredCharacteristics
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/** Default seed count for the oracle corpus; scaled by `-PfuzzSeeds` for nightly CI (P3.3 knob). */
private const val ORACLE_CORPUS_SEEDS = 16

/** Per-game turn bound: deep enough for real Aura churn, shallow enough to stay a small slice of the suite. */
private const val ORACLE_CORPUS_TURN_CAP = 40

/** Runaway decision guard per game. */
private const val ORACLE_CORPUS_DECISION_CAP = 20_000

/**
 * The corpus-wiring pass (P4.3 deliverable 5, docs/design/layer-system.md §8): the brute-force oracle
 * folded into real random-legal Aura playouts. Each seed plays a [boglesAuraConfig] game through the
 * [ScriptedGame] driver — which already invariant-checks every transition — and at **every pause** this
 * spec additionally asserts [oracleCharacteristics] set-equals the engine's [layeredCharacteristics] for
 * every battlefield object. So the equivalence is proved not only over the synthetic random boards of
 * [LayerOracleEquivalenceSpec] but over the boards the real CR 601 cast pipeline actually produces —
 * Auras cast, attached (CR 303.4f), and torn off dying creatures (CR 704.5m).
 *
 * A sibling spec rather than a callback on [dev.mtgplay.acceptance.fuzz.FuzzHarness]: the harness'
 * `inspect` hook only sees each game's *final* state, whereas the value here is checking mid-game boards
 * where several Auras are live, and the harness is card-agnostic main source this test module must not
 * reshape for a card-specific check. All randomness is the seeded core [Rng] (ADR-006).
 */
class LayerOracleCorpusSpec :
    StringSpec({

        val seeds = fuzzSeeds(ORACLE_CORPUS_SEEDS)
        "CR 613 and §8: the brute-force oracle matches the engine at every pause across ${seeds.size} real Aura games" {
            var pausesChecked = 0
            var objectsChecked = 0
            var pausesWithAura = 0
            for (seed in seeds) {
                val game = ScriptedGame.start(boglesAuraConfig(seed))
                val responder = RandomLegalResponder(seed)
                var steps = 0
                while (!game.isOver &&
                    game.state.turn.number <= ORACLE_CORPUS_TURN_CAP &&
                    steps < ORACLE_CORPUS_DECISION_CAP
                ) {
                    val state = game.state
                    var sawAura = false
                    for (obj in state.sharedZones.battlefield) {
                        objectsChecked += 1
                        if (obj.attachedTo != null) sawAura = true
                        val layered = layeredCharacteristics(state, obj.id)
                        val oracle = oracleCharacteristics(state, obj.id)
                        withClue("seed=$seed turn=${state.turn.number} id=${obj.id.value} card=${obj.card.name}") {
                            layered.power shouldBe oracle.power
                            layered.toughness shouldBe oracle.toughness
                            layered.keywords.toSet() shouldBe oracle.keywords
                            layered.manaAbilities.toSet() shouldBe oracle.manaAbilities.toSet()
                        }
                    }
                    if (sawAura) pausesWithAura += 1
                    pausesChecked += 1
                    game.respond(responder)
                    steps += 1
                }
            }
            // The playouts really did attach Auras — the oracle was exercised on live, engine-built boards.
            pausesWithAura shouldBeGreaterThan 0
            println(
                "LAYER ORACLE CORPUS: seeds=${seeds.size} pausesChecked=$pausesChecked " +
                    "objectsChecked=$objectsChecked pausesWithAura=$pausesWithAura",
            )
        }
    })
