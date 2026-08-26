package dev.mtgplay.rules

import dev.mtgplay.rules.decision.DecisionRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * Troll of Khazad-dûm's blocker-**count** restriction (CR 509.1b): "can't be blocked except by three
 * or more creatures."
 *
 * The first block restriction in the engine that is not a property of a (blocker, attacker) pairing,
 * so it is the first that the option list cannot express. It is published on the declare-blockers
 * request as a per-attacker minimum and enforced across the whole chosen set — and both halves are
 * tested here, because publishing without enforcing would offer an illegal line and enforcing without
 * publishing would hide a legal one, and ADR-005 calls both defects.
 */
class BlockerCountRestrictionSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 509.1b: every blocker is still an individually legal pairing — the restriction is on the set" {
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Troll")),
                    bobField = listOf(Combatant("Bear"), Combatant("Ogre"), Combatant("Giant")),
                )
            val request = engine.toDeclareBlockers(state, "Troll").pending<DecisionRequest.DeclareBlockers>()

            // Contrast with Silhana's evasion, which removes pairings: this one removes none, because
            // no single pairing is illegal on its own. That is exactly why it needs its own field.
            request.blockPairs() shouldContainExactlyInAnyOrder
                listOf("Bear" to "Troll", "Ogre" to "Troll", "Giant" to "Troll")
        }

        "CR 509.1b: the request publishes the attacker's minimum, so a seat can see the constraint" {
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Troll")),
                    bobField = listOf(Combatant("Bear"), Combatant("Ogre"), Combatant("Giant")),
                )
            val request = engine.toDeclareBlockers(state, "Troll").pending<DecisionRequest.DeclareBlockers>()

            val floor = request.minimumBlockers.single()
            floor.attackerCard.name shouldBe "Troll"
            floor.minimum shouldBe 3
        }

        "CR 702.110a: menace publishes a floor of two through the same CR 509.1b seam" {
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Menacer")),
                    bobField = listOf(Combatant("Bear"), Combatant("Ogre")),
                )
            val request = engine.toDeclareBlockers(state, "Menacer").pending<DecisionRequest.DeclareBlockers>()

            val floor = request.minimumBlockers.single()
            floor.attackerCard.name shouldBe "Menacer"
            floor.minimum shouldBe 2
            // Menace restricts the *set*, not the pairings: both blockers are individually offered.
            request.blockPairs() shouldContainExactlyInAnyOrder listOf("Bear" to "Menacer", "Ogre" to "Menacer")
        }

        "CR 702.110a: blocking a menace creature with one creature is rejected" {
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Menacer")),
                    bobField = listOf(Combatant("Bear"), Combatant("Ogre")),
                )
            val paused = engine.toDeclareBlockers(state, "Menacer")

            shouldThrow<IllegalArgumentException> {
                engine.declareBlocks(paused, "Bear" to "Menacer")
            }
            // Two is legal, which is what makes the floor a floor rather than a ban.
            engine.declareBlocks(paused, "Bear" to "Menacer", "Ogre" to "Menacer")
        }

        "CR 509.1b: an ordinary attacker publishes no minimum at all" {
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Ogre")),
                    bobField = listOf(Combatant("Bear")),
                )
            val request = engine.toDeclareBlockers(state, "Ogre").pending<DecisionRequest.DeclareBlockers>()

            // A floor of one restricts nothing, so it is never published — the field stays empty on
            // every board the gauntlet has played until now.
            request.minimumBlockers.shouldBeEmpty()
        }

        "CR 509.1b: blocking the Troll with one creature is rejected, not silently accepted" {
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Troll")),
                    bobField = listOf(Combatant("Bear"), Combatant("Ogre"), Combatant("Giant")),
                )
            val paused = engine.toDeclareBlockers(state, "Troll")

            shouldThrow<IllegalArgumentException> {
                engine.declareBlocks(paused, "Bear" to "Troll")
            }
        }

        "CR 509.1b: two blockers is still short of three and is rejected" {
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Troll")),
                    bobField = listOf(Combatant("Bear"), Combatant("Ogre"), Combatant("Giant")),
                )
            val paused = engine.toDeclareBlockers(state, "Troll")

            shouldThrow<IllegalArgumentException> {
                engine.declareBlocks(paused, "Bear" to "Troll", "Ogre" to "Troll")
            }
        }

        "CR 509.1b: three blockers is legal, and CR 509.2 then orders them" {
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Troll")),
                    bobField = listOf(Combatant("Bear"), Combatant("Ogre"), Combatant("Giant")),
                )
            val paused = engine.toDeclareBlockers(state, "Troll")
            val blocked =
                engine.declareBlocks(paused, "Bear" to "Troll", "Ogre" to "Troll", "Giant" to "Troll")

            // The declaration was accepted, and an attacker blocked by three needs its damage
            // assignment order (CR 509.2) — which is the next pause, not a rejection.
            blocked.pending<DecisionRequest.OrderBlockers>().options shouldHaveSize 3
        }

        "CR 509.1b: blocking with none is always legal — the restriction is not a requirement" {
            val state =
                attackStep(
                    aliceField = listOf(Combatant("Troll")),
                    bobField = listOf(Combatant("Bear"), Combatant("Ogre"), Combatant("Giant")),
                )
            val paused = engine.toDeclareBlockers(state, "Troll")

            // A defender with three untapped creatures is never *forced* to throw them under it:
            // CR 509.1b says how a creature may be blocked, never that it must be.
            engine
                .declareBlocks(paused)
                .pausedState.turn.combat
                ?.blocks
                .orEmpty()
                .shouldBeEmpty()
        }
    })
