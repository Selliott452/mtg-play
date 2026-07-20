package dev.mtgplay.rules

import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.isTargetLegal
import dev.mtgplay.rules.engine.resolveTopOfStack
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf

/** CR 608 resolution: LIFO order, target re-checking, and the graveyard move. */
class StackResolutionSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        "CR 608.1: two spells resolve in reverse casting order, with windows between resolutions" {
            val start =
                fixtureState(
                    aliceSetup =
                        SeatSetup(hand = listOf("Fixture Bolt"), battlefield = listOf("Fixture Mountain")),
                    bobSetup =
                        SeatSetup(hand = listOf("Fixture Bolt"), battlefield = listOf("Fixture Mountain")),
                )
            // Alice casts targeting bob, then passes; bob responds by casting targeting alice.
            var current = engine.advance(start, castDecision(pausedRequestOf(start), "Fixture Bolt"))
            current = engine.advance(current.pausedState, targetDecision(current.pending(), bob))
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            current = engine.advance(current.pausedState, passDecision(current.pending()))
            current = engine.advance(current.pausedState, castDecision(current.pending(), "Fixture Bolt"))
            current = engine.advance(current.pausedState, targetDecision(current.pending(), alice))
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            current.pausedState.sharedZones.stack shouldHaveSize 2

            // Bob keeps priority after casting; both pass — only the TOP spell resolves.
            current = engine.advance(current.pausedState, passDecision(current.pending()))
            current = engine.advance(current.pausedState, passDecision(current.pending()))
            var state = current.pausedState
            state.sharedZones.stack shouldHaveSize 1
            // Bob's spell (cast second) resolved first: alice lost 3, bob is untouched.
            state.players.getValue(alice).life shouldBe STARTING_LIFE - FIXTURE_BOLT_LIFE_LOSS
            state.players.getValue(bob).life shouldBe STARTING_LIFE
            // CR 117.3b + 117.4: the active player gets a fresh round before the next resolution.
            current.pending<DecisionRequest.ChooseAction>().seat shouldBe alice

            // Both pass again: the remaining (first-cast) spell resolves.
            current = engine.advance(state, passDecision(current.pending()))
            current = engine.advance(current.pausedState, passDecision(current.pending()))
            state = current.pausedState
            state.sharedZones.stack.shouldBeEmpty()
            state.players.getValue(bob).life shouldBe STARTING_LIFE - FIXTURE_BOLT_LIFE_LOSS
            state.events.filterIsInstance<GameEvent.SpellResolved>() shouldHaveSize 2
        }

        "CR 608.2b: a target that is no longer legal is detected by the re-check" {
            val state = fixtureState(aliceSetup = SeatSetup(), bobSetup = SeatSetup())
            isTargetLegal(state, TargetSpec.AnyTarget, Target.Player(bob)).shouldBeTrue()
            // No reachable two-player state unseats a player, so the illegal case is exercised
            // directly: a target naming an unseated player is not in the legal enumeration.
            isTargetLegal(state, TargetSpec.AnyTarget, Target.Player(PlayerId(7))).shouldBeFalse()
        }

        "CR 608.2b: a spell whose only target is illegal on resolution fizzles to the graveyard" {
            // Unreachable end-to-end in P2.1 (players cannot become illegal targets in a
            // two-player game), so the resolution step is driven directly with a handcrafted
            // stack entry whose target is an unseated player.
            val base = fixtureState(aliceSetup = SeatSetup(), bobSetup = SeatSetup())
            val (spellId, allocated) = base.allocateObjectId()
            val entry =
                StackEntry.Spell(
                    obj = GameObject(spellId, CardRef("Fixture Bolt"), alice),
                    controller = alice,
                    targets = persistentListOf(Target.Player(PlayerId(7))),
                    definition = fixtureBolt,
                )
            val withSpell =
                allocated.copy(
                    sharedZones = allocated.sharedZones.copy(stack = allocated.sharedZones.stack.adding(entry)),
                )

            val result = resolveTopOfStack(withSpell)
            val state = result.pausedState
            // None of the spell's instructions were performed: every life total is untouched.
            state.players.getValue(alice).life shouldBe STARTING_LIFE
            state.players.getValue(bob).life shouldBe STARTING_LIFE
            // The card still went to its owner's graveyard as a new object (CR 608.2m, CR 400.7).
            state.sharedZones.stack.shouldBeEmpty()
            val inGraveyard =
                state.players
                    .getValue(alice)
                    .graveyard
                    .single()
            inGraveyard.card shouldBe CardRef("Fixture Bolt")
            val fizzle = state.events.filterIsInstance<GameEvent.SpellFizzled>().single()
            fizzle.objectId shouldBe spellId
            fizzle.graveyardObjectId shouldBe inGraveyard.id
            state.events.filterIsInstance<GameEvent.SpellResolved>().shouldBeEmpty()
        }

        "CR 704.5a: a player dying to a resolution ends the game before any further window" {
            val start =
                fixtureState(
                    aliceSetup =
                        SeatSetup(hand = listOf("Fixture Bolt"), battlefield = listOf("Fixture Mountain")),
                    bobSetup = SeatSetup(life = FIXTURE_BOLT_LIFE_LOSS),
                )
            var current = engine.advance(start, castDecision(pausedRequestOf(start), "Fixture Bolt"))
            current = engine.advance(current.pausedState, targetDecision(current.pending(), bob))
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            current = engine.advance(current.pausedState, passDecision(current.pending()))
            val over =
                engine
                    .advance(current.pausedState, passDecision(current.pending()))
                    .shouldBeInstanceOf<AdvanceResult.GameOver>()
            over.result.loser shouldBe bob
            over.state.players
                .getValue(bob)
                .life shouldBe 0
        }

        "an ObjectId is never reused across the cast and resolution rebirths (CR 400.7)" {
            val start =
                fixtureState(
                    aliceSetup = SeatSetup(hand = listOf("Fixture Bolt"), battlefield = listOf("Fixture Mountain")),
                    bobSetup = SeatSetup(),
                )
            val handId =
                start.players
                    .getValue(alice)
                    .hand
                    .single()
                    .id
            var current = engine.advance(start, castDecision(pausedRequestOf(start), "Fixture Bolt"))
            current = engine.advance(current.pausedState, targetDecision(current.pending(), bob))
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            val stackId =
                current.pausedState.sharedZones.stack
                    .single()
                    .obj.id
            current = engine.advance(current.pausedState, passDecision(current.pending()))
            current = engine.advance(current.pausedState, passDecision(current.pending()))
            val graveId =
                current.pausedState.players
                    .getValue(alice)
                    .graveyard
                    .single()
                    .id
            setOf(handId, stackId, graveId) shouldHaveSize 3
        }
    })
