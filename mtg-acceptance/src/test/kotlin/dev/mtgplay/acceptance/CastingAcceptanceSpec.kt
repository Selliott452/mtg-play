package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.RandomLegalResponder
import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.acceptance.invariant.InvariantChecker
import dev.mtgplay.acceptance.replay.fingerprint
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

// A seed whose starting hand for alice contains at least one Fixture Bolt (asserted below), so
// the scripted duel is deterministic without searching.
private const val SCRIPTED_SEED: Long = 7L

/**
 * Casting end to end through the acceptance driver: every transition invariant-checked, and a
 * recorded game with casting decisions reproduced exactly from `(scenario, decisions)`
 * (ADR-006).
 */
class CastingAcceptanceSpec :
    StringSpec({

        fun scriptedDuel(): ScriptedGame {
            val game = ScriptedGame.startFrom(fixtureMatchStart(SCRIPTED_SEED, startingPlayer = alice))
            val window = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            val castIndex =
                window.options.indexOfFirst {
                    it is PriorityOption.CastSpell && it.card == CardRef("Fixture Bolt")
                }
            castIndex shouldBeGreaterThanOrEqual 0
            game.apply(Decision.SingleSelect(window.id, castIndex))
            val targets = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
            game.apply(Decision.SingleSelect(targets.id, targets.options.indexOf(Target.Player(bob))))
            val payment = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
            game.apply(Decision.SingleSelect(payment.id, 0))
            // Caster's post-cast window, then the opponent's response window, then resolution.
            return game.pass().pass()
        }

        "CR 601 + CR 608: a scripted Fixture Bolt duel casts, responds, and resolves under the invariant checker" {
            val game = scriptedDuel()
            val state = game.state
            state.players.getValue(bob).life shouldBe STARTING_LIFE - FIXTURE_BOLT_LIFE_LOSS
            state.sharedZones.stack.shouldBeEmpty()
            state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldBe listOf(CardRef("Fixture Bolt"))
            state.events.filterIsInstance<GameEvent.SpellResolved>() shouldHaveSize 1
            InvariantChecker.check(state, game.cardBaseline).shouldBeEmpty()
        }

        "ADR-006: a random fixture game with casting decisions replays to the same fingerprint and event log" {
            val seed = 11L
            val original =
                ScriptedGame
                    .startFrom(fixtureMatchStart(seed))
                    .playToCompletion(RandomLegalResponder(seed), turnCap = FIXTURE_TURN_CAP)
            original.result.shouldNotBeNull()
            // The scenario start is a pure function of the seed, so the replay rebuilds the same
            // state and feeds the recorded decisions — reproduction on both axes (ADR-006).
            val replayed = ScriptedGame.startFrom(fixtureMatchStart(seed))
            original.decisions.forEach { replayed.apply(it) }
            fingerprint(replayed.state) shouldBe fingerprint(original.state)
            replayed.state.events shouldBe original.state.events
        }

        "ADR-006: the scripted duel replays decision-for-decision" {
            val original = scriptedDuel()
            val replayed = ScriptedGame.startFrom(fixtureMatchStart(SCRIPTED_SEED, startingPlayer = alice))
            original.decisions.forEach { replayed.apply(it) }
            fingerprint(replayed.state) shouldBe fingerprint(original.state)
            replayed.state.events shouldBe original.state.events
        }
    })
