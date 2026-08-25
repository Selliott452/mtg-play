package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.invariant.InvariantChecker
import dev.mtgplay.acceptance.replay.ReplayHarness
import dev.mtgplay.cards.LIGHTNING_BOLT_DAMAGE
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.event.LossReason
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.DamageSource
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.MatchResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Death mid-stack, pinned on real cards (P2.3, closing the gap the P2.2 report flagged): with
 * both players within Bolt range, a two-Bolt stack ends the game at the *first* lethal
 * resolution — the CR 704.5a state-based action performed when state-based actions are checked
 * before the next priority grant (CR 704.3, CR 117.3b) — and the initiator's Bolt is still on
 * the stack, unresolved, in the final state. Both directions are scripted, and the game
 * replays decision-for-decision (ADR-006).
 */
class DeathMidStackAcceptanceSpec :
    StringSpec({

        fun verifyFirstLethalResolutionEndsGame(initiator: PlayerId) {
            val responder = opponentOf(initiator)
            val outcome = deathMidStackDuel(initiator)
            val game = outcome.game
            val final = game.state

            // CR 704.5a via CR 704.3: over at the first lethal resolution, and the MatchResult
            // names the right player and reason.
            game.isOver.shouldBeTrue()
            game.result shouldBe
                MatchResult(winner = responder, loser = initiator, reason = LossReason.LIFE_TOTAL_ZERO_OR_LESS)
            final.players.getValue(initiator).life shouldBe BOLT_LETHAL_LIFE - LIGHTNING_BOLT_DAMAGE
            // The responder is untouched: the Bolt aimed at them never resolved.
            final.players.getValue(responder).life shouldBe BOLT_LETHAL_LIFE

            // The initiator's unresolved Bolt remains on the stack in the final state, its cast
            // record intact (CR 405.2).
            val stranded =
                final.sharedZones.stack
                    .single()
                    .shouldBeInstanceOf<StackEntry.Spell>()
            stranded.obj.id shouldBe outcome.initiatorBoltId
            stranded.obj.card shouldBe CardRef("Lightning Bolt")
            stranded.controller shouldBe initiator
            stranded.targets.single() shouldBe Target.Player(responder)

            // Exactly one resolution happened in the duel (CR 608.1 resolved only the top), and
            // the log closes with the CR 704.5a death — nothing at all follows GameEnded.
            val duelEvents = final.events.drop(outcome.eventsBeforeDuel)
            duelEvents.filterIsInstance<GameEvent.SpellResolved>() shouldHaveSize 1
            duelEvents.filterIsInstance<GameEvent.SpellFizzled>().shouldBeEmpty()
            val tail = final.events.takeLast(5)
            // CR 120.1: the damage names its source, and here that is load-bearing — the *responder's*
            // Bolt is the one that resolved, and the source is what says so (`FW-PREVENT`).
            tail[0] shouldBe
                GameEvent.DamageDealt(
                    DamageSource(outcome.responderBoltId, CardRef("Lightning Bolt")),
                    Target.Player(initiator),
                    LIGHTNING_BOLT_DAMAGE,
                )
            tail[1] shouldBe
                GameEvent.LifeChanged(initiator, -LIGHTNING_BOLT_DAMAGE, BOLT_LETHAL_LIFE - LIGHTNING_BOLT_DAMAGE)
            val resolved = tail[2].shouldBeInstanceOf<GameEvent.SpellResolved>()
            resolved.objectId shouldBe outcome.responderBoltId
            resolved.controller shouldBe responder
            tail[3] shouldBe GameEvent.PlayerLost(initiator, LossReason.LIFE_TOTAL_ZERO_OR_LESS)
            tail[4] shouldBe GameEvent.GameEnded(winner = responder, loser = initiator)

            InvariantChecker.check(final, game.cardBaseline).shouldBeEmpty()
        }

        "CR 704.5a: bob's answering Bolt kills alice mid-stack — game over at the first lethal resolution" {
            verifyFirstLethalResolutionEndsGame(initiator = alice)
        }

        "CR 704.5a: alice's answering Bolt kills bob mid-stack — the same pin in the other direction" {
            verifyFirstLethalResolutionEndsGame(initiator = bob)
        }

        "ADR-006: the death-mid-stack game replays decision-for-decision to the same stacked final state" {
            val outcome = deathMidStackDuel(alice)
            val replayed = ReplayHarness.verifyReproduces(outcome.config, outcome.game)
            replayed.fingerprintMatches.shouldBeTrue()
            replayed.eventLogMatches.shouldBeTrue()
            replayed.reproduced.shouldBeTrue()
        }
    })
