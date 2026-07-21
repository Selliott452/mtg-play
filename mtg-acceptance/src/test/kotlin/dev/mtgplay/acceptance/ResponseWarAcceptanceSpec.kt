package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.acceptance.invariant.InvariantChecker
import dev.mtgplay.acceptance.replay.ReplayHarness
import dev.mtgplay.cards.LIGHTNING_BOLT_DAMAGE
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.DecisionRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The P2.3 response war: a three-deep Bolt stack on real cards, resolving strictly LIFO
 * (CR 608.1) with a fresh priority round between resolutions (CR 117.3b) and the CR 117.4
 * pass-in-succession count restarting after every cast and every resolution — each restart
 * observed directly in the seats' [PriorityStatus].
 */
class ResponseWarAcceptanceSpec :
    StringSpec({

        // Alice can double-Bolt from two Mountains on turn 3; bob can answer from one.
        val warSeed =
            seedWithOpeningHands({ seed -> burnConfig(seed, startingPlayer = alice) }) { aliceHand, bobHand ->
                aliceHand.count { it == "Mountain" } >= 2 &&
                    aliceHand.count { it == "Lightning Bolt" } >= 2 &&
                    bobHand.count { it == "Mountain" } >= 1 &&
                    bobHand.count { it == "Lightning Bolt" } >= 1
            }

        fun warConfig() = burnConfig(warSeed, startingPlayer = alice)

        // Plays the war: alice Bolts bob, bob answers with a Bolt at alice, alice answers the
        // answer with a second Bolt at bob — three deep, ready to resolve.
        fun scriptedWar(): Pair<ScriptedGame, List<ObjectId>> {
            val game = ScriptedGame.start(warConfig())
            game.passUntil { it.turn.phase == TurnPhase.PRECOMBAT_MAIN }
            playLand(game)
            game.passUntil { it.turn.number == 2 && it.turn.phase == TurnPhase.PRECOMBAT_MAIN }
            playLand(game)
            game.passUntil { it.turn.number == 3 && it.turn.phase == TurnPhase.PRECOMBAT_MAIN }
            playLand(game)

            // Bolt one: alice, from her own main.
            val bolt1 = castBoltAt(game, bob)
            game.state.players
                .getValue(alice)
                .priorityStatus shouldBe PriorityStatus.HOLDS_PRIORITY
            game.pass()

            // CR 117.3d: alice's pass stands when bob's window opens...
            val bobWindow = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            bobWindow.seat shouldBe bob
            game.state.players
                .getValue(alice)
                .priorityStatus shouldBe PriorityStatus.HAS_PASSED
            // Bolt two: bob's response.
            val bolt2 = castBoltAt(game, alice)
            // ...and CR 117.4: bob's cast restarts the pass-in-succession count — alice's
            // standing pass is cleared.
            game.state.players
                .getValue(alice)
                .priorityStatus shouldBe PriorityStatus.NONE
            game.state.players
                .getValue(bob)
                .priorityStatus shouldBe PriorityStatus.HOLDS_PRIORITY
            game.pass()

            val aliceWindow = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            aliceWindow.seat shouldBe alice
            game.state.players
                .getValue(bob)
                .priorityStatus shouldBe PriorityStatus.HAS_PASSED
            // Bolt three: alice responds to the response, from her second Mountain.
            val bolt3 = castBoltAt(game, bob)
            game.state.players
                .getValue(bob)
                .priorityStatus shouldBe PriorityStatus.NONE

            // The stack is three deep, bottom-to-top in cast order (CR 405.2).
            game.state.sharedZones.stack
                .filterIsInstance<StackEntry.Spell>()
                .map { it.obj.id } shouldBe listOf(bolt1, bolt2, bolt3)

            return game to listOf(bolt1, bolt2, bolt3)
        }

        "CR 608.1 + CR 117.3b + CR 117.4: a three-Bolt war resolves LIFO, one spell per completed round" {
            val (game, bolts) = scriptedWar()
            val (bolt1, bolt2, bolt3) = bolts

            // Round one: alice and bob pass in succession; only the TOP Bolt resolves.
            game.pass()
            game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>().seat shouldBe bob
            game.pass()
            game.state.sharedZones.stack shouldHaveSize 2
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - LIGHTNING_BOLT_DAMAGE
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE
            // CR 117.3b: the active player opens the fresh round; CR 117.4: no standing passes.
            game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>().seat shouldBe alice
            game.state.players
                .getValue(alice)
                .priorityStatus shouldBe PriorityStatus.HOLDS_PRIORITY
            game.state.players
                .getValue(bob)
                .priorityStatus shouldBe PriorityStatus.NONE

            // Round two: bob's response resolves against alice.
            game.pass()
            game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>().seat shouldBe bob
            game.pass()
            game.state.sharedZones.stack shouldHaveSize 1
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE - LIGHTNING_BOLT_DAMAGE
            game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>().seat shouldBe alice

            // Round three: the first-cast Bolt, buried since the start, resolves last.
            game.pass()
            game.pass()
            game.state.sharedZones.stack
                .shouldBeEmpty()
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - 2 * LIGHTNING_BOLT_DAMAGE

            // CR 608.1: the event log shows resolution in exactly reverse casting order.
            val castOrder =
                game.state.events
                    .filterIsInstance<GameEvent.SpellCast>()
                    .map { it.objectId }
            val resolvedOrder =
                game.state.events
                    .filterIsInstance<GameEvent.SpellResolved>()
                    .map { it.objectId }
            castOrder shouldBe listOf(bolt1, bolt2, bolt3)
            resolvedOrder shouldBe listOf(bolt3, bolt2, bolt1)

            // CR 608.2m: every Bolt is in its owner's graveyard — two of alice's, one of bob's.
            game.state.players
                .getValue(alice)
                .graveyard
                .count { it.card == CardRef("Lightning Bolt") } shouldBe 2
            game.state.players
                .getValue(bob)
                .graveyard
                .count { it.card == CardRef("Lightning Bolt") } shouldBe 1
            InvariantChecker.check(game.state, game.cardBaseline).shouldBeEmpty()
        }

        "ADR-006: the response war replays decision-for-decision to the same fingerprint and event log" {
            val (game, _) = scriptedWar()
            game
                .pass()
                .pass()
                .pass()
                .pass()
                .pass()
                .pass()
            val outcome = ReplayHarness.verifyReproduces(warConfig(), game)
            outcome.fingerprintMatches.shouldBeTrue()
            outcome.eventLogMatches.shouldBeTrue()
            outcome.reproduced.shouldBeTrue()
        }
    })
