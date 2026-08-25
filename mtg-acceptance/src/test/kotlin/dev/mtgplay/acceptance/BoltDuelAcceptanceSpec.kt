package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.RandomLegalResponder
import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.acceptance.invariant.InvariantChecker
import dev.mtgplay.acceptance.replay.ReplayHarness
import dev.mtgplay.cards.LIGHTNING_BOLT_DAMAGE
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.event.LossReason
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * The first real-card acceptance games (P2.2): Mountain and Lightning Bolt flowing through the
 * whole engine — play-land special action, CR 601 cast, CR 608 resolution, the CR 120 damage
 * primitive, and the CR 704.5a bolt death — every transition invariant-checked, and every
 * recorded game reproducible from `(config, decisions)` (ADR-006).
 */
class BoltDuelAcceptanceSpec :
    StringSpec({

        // A seed whose opening hand for alice holds a Mountain and a Bolt, found
        // deterministically rather than hardcoded.
        val duelSeed =
            seedWithOpeningHand(alice, { seed -> burnConfig(seed, startingPlayer = alice) }) { hand ->
                hand.contains("Mountain") && hand.contains("Lightning Bolt")
            }

        fun duelConfig() = burnConfig(duelSeed, startingPlayer = alice)

        fun scriptedDuel(): ScriptedGame {
            val game = ScriptedGame.start(duelConfig())
            // Turn 1: pass the upkeep round (the draw step is skipped, CR 103.8a) into alice's
            // precombat main.
            game.passUntil { it.turn.phase == TurnPhase.PRECOMBAT_MAIN }

            // Play the Mountain (CR 116.2a special action).
            val main = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            main.seat shouldBe alice
            val playIndex = main.options.indexOfFirst { it is PriorityOption.PlayLand }
            playIndex shouldBeGreaterThanOrEqual 0
            game.apply(Decision.SingleSelect(main.id, playIndex))

            // CR 116.4: alice retained priority. Cast the Bolt from the same window.
            val postLand = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            postLand.seat shouldBe alice
            val castIndex =
                postLand.options.indexOfFirst {
                    it is PriorityOption.CastSpell && it.card == CardRef("Lightning Bolt")
                }
            castIndex shouldBeGreaterThanOrEqual 0
            game.apply(Decision.SingleSelect(postLand.id, castIndex))

            // CR 601.2c: any target — target the opponent.
            val targets = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
            game.apply(Decision.SingleSelect(targets.id, targets.options.indexOf(Target.Player(bob))))

            // CR 601.2g: exactly one plan exists — tap the lone Mountain.
            val payment = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
            payment.options shouldHaveSize 1
            game.apply(Decision.SingleSelect(payment.id, 0))
            return game
        }

        "CR 116.2a + CR 601 + CR 608: turn 1 — play Mountain, Bolt the opponent through a response window" {
            val game = scriptedDuel()

            // The land play happened: one untapped-then-tapped real Mountain, reborn on the
            // battlefield with a fresh id (CR 400.7), narrated by LandPlayed.
            val landPlayed =
                game.state.events
                    .filterIsInstance<GameEvent.LandPlayed>()
                    .single()
            landPlayed.player shouldBe alice
            landPlayed.card shouldBe CardRef("Mountain")
            val mountain =
                game.state.sharedZones.battlefield
                    .single()
            mountain.id shouldBe landPlayed.objectId
            // CR 601.2g: paying {R} tapped it.
            mountain.tapped.shouldBeTrue()

            // The caster's post-cast window (CR 117.3b), stack holding the Bolt.
            val stackId =
                game.state.sharedZones.stack
                    .single()
                    .shouldBeInstanceOf<StackEntry.Spell>()
                    .obj.id
            game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>().seat shouldBe alice
            game.pass()

            // CR 117.3d: the opponent's response window is honoured before resolution.
            val response = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            response.seat shouldBe bob
            game.state.sharedZones.stack shouldHaveSize 1
            game.pass()

            // CR 608.2: resolved — 3 damage to bob, life 20 -> 17, damage-then-life in events.
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - LIGHTNING_BOLT_DAMAGE
            val events = game.state.events
            val damageIndex =
                events.indexOfFirst {
                    it is GameEvent.DamageDealt &&
                        it.recipient == Target.Player(bob) &&
                        it.amount == LIGHTNING_BOLT_DAMAGE
                }
            damageIndex shouldBeGreaterThanOrEqual 0
            // CR 120.1: the damage names the object that dealt it — the resolving Bolt itself
            // (`FW-PREVENT`). Asserted here rather than in the equality above so the match stays
            // pinned to recipient and amount and this stays a separate, named claim.
            (events[damageIndex] as GameEvent.DamageDealt).source.card shouldBe CardRef("Lightning Bolt")
            events[damageIndex + 1] shouldBe
                GameEvent.LifeChanged(bob, -LIGHTNING_BOLT_DAMAGE, STARTING_LIFE - LIGHTNING_BOLT_DAMAGE)

            // CR 608.2m + CR 400.7: the Bolt card is in its owner's graveyard as a new object.
            game.state.sharedZones.stack
                .shouldBeEmpty()
            val inGraveyard =
                game.state.players
                    .getValue(alice)
                    .graveyard
                    .single()
            inGraveyard.card shouldBe CardRef("Lightning Bolt")
            inGraveyard.id shouldNotBe stackId
            game.state.events.filterIsInstance<GameEvent.SpellResolved>() shouldHaveSize 1
            InvariantChecker.check(game.state, game.cardBaseline).shouldBeEmpty()
        }

        "CR 120.3a vs CR 119.3c: the Bolt's life change is damage — DamageDealt precedes it; no bare life loss" {
            val game = scriptedDuel().pass().pass()
            val events = game.state.events
            // Every LifeChanged in a real-card P2.2 game is the result of damage: the event
            // immediately before it is the DamageDealt that caused it (CR 120.3a).
            val lifeChanges = events.withIndex().filter { it.value is GameEvent.LifeChanged }
            lifeChanges shouldHaveSize 1
            lifeChanges.forEach { (index, _) ->
                events[index - 1].shouldBeInstanceOf<GameEvent.DamageDealt>()
            }
        }

        "CR 704.5a: accumulated Bolts end the game — the loser's life is 0 or less, all of it damage" {
            val game =
                ScriptedGame
                    .start(burnConfig(seed = 3, startingPlayer = alice))
                    .playToCompletion(BURN_OPPONENT, turnCap = 60)
            val result = game.result.shouldNotBeNull()
            result.reason shouldBe LossReason.LIFE_TOTAL_ZERO_OR_LESS
            game.state.players
                .getValue(result.loser)
                .life shouldBeLessThanOrEqual 0
            // The kill was accumulated Bolt damage: at least ceil(20 / 3) = 7 hits landed.
            val damageOnLoser =
                game.state.events
                    .filterIsInstance<GameEvent.DamageDealt>()
                    .filter { it.recipient == Target.Player(result.loser) }
            damageOnLoser.size shouldBeGreaterThanOrEqual 7
            damageOnLoser.sumOf { it.amount } shouldBeGreaterThanOrEqual STARTING_LIFE
            InvariantChecker.check(game.state, game.cardBaseline).shouldBeEmpty()
        }

        "ADR-006: the scripted duel replays decision-for-decision to the same fingerprint and event log" {
            val original = scriptedDuel().pass().pass()
            val outcome = ReplayHarness.verifyReproduces(duelConfig(), original)
            outcome.fingerprintMatches.shouldBeTrue()
            outcome.eventLogMatches.shouldBeTrue()
            outcome.reproduced.shouldBeTrue()
        }

        "ADR-006: a random real-card game replays to the same fingerprint and event log" {
            val seed = 11L
            val original =
                ScriptedGame
                    .start(burnConfig(seed))
                    .playToCompletion(RandomLegalResponder(seed), turnCap = REAL_CARD_TURN_CAP)
            original.result.shouldNotBeNull()
            val outcome = ReplayHarness.verifyReproduces(burnConfig(seed), original)
            outcome.reproduced.shouldBeTrue()
        }
    })
