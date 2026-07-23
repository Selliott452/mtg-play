package dev.mtgplay.rules

import dev.mtgplay.rules.decision.DecisionRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder

/**
 * Silhana Ledgewalker's evasion (CR 509.1b): "can't be blocked except by creatures with flying." The
 * block-legality seam reads the printed evasion beside the flying check and enumerates only flying
 * blockers as legal for such an attacker — the same requirement flying itself imposes.
 */
class EvasionScenarioSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 509.1b: a blockable-only-by-flying attacker is blockable by a flyer, not by a ground creature" {
            // Skulker (blockable only by flying) attacks; bob has a Flyer (flying) and a Bear (ground).
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Skulker")),
                    bobField = listOf(Combatant("Flyer"), Combatant("Bear")),
                )
            val request = engine.toDeclareBlockers(state, "Skulker").pending<DecisionRequest.DeclareBlockers>()

            // Only the flyer may block it; the ground Bear may not (no (Bear, Skulker) pairing exists).
            request.blockPairs() shouldContainExactly listOf("Flyer" to "Skulker")
        }

        "CR 509.1b: the same ground creature can block a creature without the evasion" {
            // Contrast: a plain Ogre attacker is blockable by both the Flyer and the ground Bear.
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Ogre")),
                    bobField = listOf(Combatant("Flyer"), Combatant("Bear")),
                )
            val request = engine.toDeclareBlockers(state, "Ogre").pending<DecisionRequest.DeclareBlockers>()

            request.blockPairs() shouldContainExactlyInAnyOrder listOf("Flyer" to "Ogre", "Bear" to "Ogre")
        }
    })
