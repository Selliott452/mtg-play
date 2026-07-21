package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.acceptance.invariant.InvariantChecker
import dev.mtgplay.cards.LIGHTNING_BOLT_DAMAGE
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The P2.3 mana-constrained window: with exactly two untapped Mountains, exactly two Bolts fit
 * in one priority window — cast back-to-back with sequential payment (CR 601.2g) tapping a
 * distinct Mountain each — and the third Bolt is *absent from enumeration*, not merely doomed
 * to fail (ADR-005: an unaffordable cast is never surfaced). The untap step's turn-based
 * action (CR 502.2) restores affordability, and with it the option, the next turn.
 */
class ManaConstrainedWindowAcceptanceSpec :
    StringSpec({

        // Alice's opening hand funds the scene by turn 3: two Mountains, at least three Bolts.
        val windowSeed =
            seedWithOpeningHand(alice, { seed -> burnConfig(seed, startingPlayer = alice) }) { hand ->
                hand.count { it == "Mountain" } >= 2 && hand.count { it == "Lightning Bolt" } >= 3
            }

        fun boltCastOptions(request: DecisionRequest.ChooseAction): List<PriorityOption.CastSpell> =
            request.options.filterIsInstance<PriorityOption.CastSpell>()

        fun boltsInHand(game: ScriptedGame): Int =
            game.state.players
                .getValue(alice)
                .hand
                .count { it.card == CardRef("Lightning Bolt") }

        "ADR-005 + CR 601.2g: two Mountains fund two back-to-back Bolts; the third is not enumerated until untap" {
            val game = ScriptedGame.start(burnConfig(windowSeed, startingPlayer = alice))
            game.passUntil { it.turn.phase == TurnPhase.PRECOMBAT_MAIN }
            playLand(game)
            game.passUntil { it.turn.number == 3 && it.turn.phase == TurnPhase.PRECOMBAT_MAIN }
            playLand(game)
            untappedMountains(game.state, alice) shouldBe 2

            // With two untapped Mountains every Bolt in hand is individually affordable, so the
            // window enumerates one CastSpell per Bolt (ADR-005, completeness direction).
            val bolts = boltsInHand(game)
            bolts shouldBeGreaterThanOrEqual 3
            val fullWindow = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            boltCastOptions(fullWindow) shouldHaveSize bolts
            boltCastOptions(fullWindow).all { it.card == CardRef("Lightning Bolt") }.shouldBeTrue()

            // Cast two Bolts back-to-back: the caster receives priority again after each cast
            // (CR 117.3b), so both fit before a single pass.
            castBoltAt(game, bob)
            untappedMountains(game.state, alice) shouldBe 1
            val betweenWindow = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            betweenWindow.seat shouldBe alice
            boltCastOptions(betweenWindow) shouldHaveSize bolts - 1
            castBoltAt(game, bob)

            // CR 601.2g executed sequentially: each cast tapped its own Mountain.
            untappedMountains(game.state, alice) shouldBe 0
            val taps = game.state.events.filterIsInstance<GameEvent.ObjectTapped>()
            taps shouldHaveSize 2
            taps.map { it.objectId }.distinct() shouldHaveSize 2

            // ADR-005, soundness direction: Bolts remain in hand, but with no untapped source
            // and an empty pool no payment plan exists, so no cast is enumerated at all.
            boltsInHand(game) shouldBeGreaterThanOrEqual 1
            val spentWindow = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            spentWindow.seat shouldBe alice
            boltCastOptions(spentWindow).shouldBeEmpty()

            // Resolve both Bolts (CR 608.1); the window after each resolution still offers no
            // cast — the Mountains stay tapped for the rest of the turn.
            game.pass()
            game.pass()
            game.state.sharedZones.stack shouldHaveSize 1
            game.pass()
            game.pass()
            game.state.sharedZones.stack
                .shouldBeEmpty()
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - 2 * LIGHTNING_BOLT_DAMAGE
            val afterResolution = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            afterResolution.seat shouldBe alice
            boltCastOptions(afterResolution).shouldBeEmpty()

            // CR 502.2: alice's next untap step untaps her Mountains, and the cast option
            // returns to her turn-5 main — one option per Bolt again.
            game.passUntil { it.turn.number == 5 && it.turn.phase == TurnPhase.PRECOMBAT_MAIN }
            untappedMountains(game.state, alice) shouldBe 2
            val nextTurnWindow = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            nextTurnWindow.seat shouldBe alice
            boltsInHand(game) shouldBeGreaterThanOrEqual 1
            boltCastOptions(nextTurnWindow) shouldHaveSize boltsInHand(game)

            InvariantChecker.check(game.state, game.cardBaseline).shouldBeEmpty()
        }
    })
