package dev.mtgplay.rules

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.event.LossReason
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** The CR 601 casting pipeline, end to end over the fixture pool. */
class CastingPipelineSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        fun boltState() =
            fixtureState(
                aliceSetup = SeatSetup(hand = listOf("Fixture Bolt"), battlefield = listOf("Fixture Mountain")),
                bobSetup = SeatSetup(),
            )

        "CR 601.2: a fixture Bolt cast runs propose, targets, payment, and completion in stage order" {
            val start = boltState()
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(start)
            val afterChoice = engine.advance(start, castDecision(window, "Fixture Bolt"))
            val targetsRequest = afterChoice.pending<DecisionRequest.ChooseTargets>()
            targetsRequest.seat shouldBe alice
            targetsRequest.options shouldBe listOf(Target.Player(alice), Target.Player(bob))

            val afterTargets = engine.advance(afterChoice.pausedState, targetDecision(targetsRequest, bob))
            val paymentRequest = afterTargets.pending<DecisionRequest.ChoosePaymentPlan>()
            // Architect decision, P2.1: the payment decision surfaces even with exactly one plan.
            paymentRequest.options shouldHaveSize 1

            val afterCast = engine.advance(afterTargets.pausedState, planDecision(paymentRequest))
            val castState = afterCast.pausedState
            // CR 601.2a: the card left the hand and waits on the stack as a new object.
            castState.players
                .getValue(alice)
                .hand
                .shouldBeEmpty()
            val entry =
                castState.sharedZones.stack
                    .single()
                    .shouldBeInstanceOf<StackEntry.Spell>()
            entry.controller shouldBe alice
            entry.targets shouldBe listOf(Target.Player(bob))
            entry.obj.id.value shouldBeGreaterThanOrEqual start.nextObjectId
            // CR 601.2g: the payment tapped the Mountain and drained the pool exactly.
            castState.sharedZones.battlefield
                .single()
                .tapped
                .shouldBeTrue()
            castState.players
                .getValue(alice)
                .manaPool
                .shouldBeEmpty()
            // CR 601 stage order in the emitted events.
            val eventOrder =
                castState.events
                    .filterIsInstance<GameEvent>()
                    .map { it::class.simpleName }
                    .filter {
                        it in
                            setOf("SpellProposed", "TargetsChosen", "ManaAbilityActivated", "ManaAdded", "SpellCast")
                    }
            eventOrder shouldBe
                listOf("SpellProposed", "TargetsChosen", "ManaAbilityActivated", "ManaAdded", "SpellCast")
            // CR 117.3b: the caster receives priority after casting.
            afterCast.pending<DecisionRequest.ChooseAction>().seat shouldBe alice
        }

        "CR 117.4 and CR 608: the opponent gets a response window, and all passing resolves the spell" {
            val start = boltState()
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(start)
            val afterCast =
                engine
                    .advance(start, castDecision(window, "Fixture Bolt"))
                    .let { engine.advance(it.pausedState, targetDecision(it.pending(), bob)) }
                    .let { engine.advance(it.pausedState, planDecision(it.pending())) }
            val casterWindow = afterCast.pending<DecisionRequest.ChooseAction>()
            val stackEntry =
                afterCast.pausedState.sharedZones.stack
                    .single()
                    .shouldBeInstanceOf<StackEntry.Spell>()

            // The opponent's response window opens before anything resolves (CR 117.3d).
            val bobWindowResult = engine.advance(afterCast.pausedState, passDecision(casterWindow))
            val bobWindow = bobWindowResult.pending<DecisionRequest.ChooseAction>()
            bobWindow.seat shouldBe bob
            bobWindowResult.pausedState.sharedZones.stack shouldHaveSize 1

            // All players passed in succession: the spell resolves (CR 608.1, CR 608.2).
            val resolved = engine.advance(bobWindowResult.pausedState, passDecision(bobWindow))
            val resolvedState = resolved.pausedState
            resolvedState.sharedZones.stack.shouldBeEmpty()
            // The lose-life effect applied to the chosen target (CR 119.3c).
            resolvedState.players.getValue(bob).life shouldBe STARTING_LIFE - FIXTURE_BOLT_LIFE_LOSS
            // CR 608.2m + CR 400.7: the card ended in its owner's graveyard as a fresh object.
            val inGraveyard =
                resolvedState.players
                    .getValue(alice)
                    .graveyard
                    .single()
            inGraveyard.card shouldBe CardRef("Fixture Bolt")
            inGraveyard.id shouldNotBe stackEntry.obj.id
            resolvedState.events.filterIsInstance<GameEvent.SpellResolved>() shouldHaveSize 1
            // CR 117.3b: the active player receives priority after the resolution.
            resolved.pending<DecisionRequest.ChooseAction>().seat shouldBe alice
        }

        "CR 601.2c: a spell that targets nothing skips the targets decision" {
            val start =
                fixtureState(
                    aliceSetup =
                        SeatSetup(hand = listOf("Fixture Meditation"), battlefield = listOf("Fixture Mountain")),
                    bobSetup = SeatSetup(),
                )
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(start)
            engine
                .advance(start, castDecision(window, "Fixture Meditation"))
                .pending<DecisionRequest.ChoosePaymentPlan>()
        }

        "CR 117.4: a cast resets the pass flags — a prior pass does not survive an intervening cast" {
            val start =
                fixtureState(
                    aliceSetup = SeatSetup(),
                    bobSetup = SeatSetup(hand = listOf("Fixture Bolt"), battlefield = listOf("Fixture Mountain")),
                )
            // Alice passes; bob responds by casting at instant speed.
            val aliceWindow = pausedRequestOf<DecisionRequest.ChooseAction>(start)
            val bobWindowResult = engine.advance(start, passDecision(aliceWindow))
            val bobWindow = bobWindowResult.pending<DecisionRequest.ChooseAction>()
            bobWindow.seat shouldBe bob
            val afterCast =
                engine
                    .advance(bobWindowResult.pausedState, castDecision(bobWindow, "Fixture Bolt"))
                    .let { engine.advance(it.pausedState, targetDecision(it.pending(), alice)) }
                    .let { engine.advance(it.pausedState, planDecision(it.pending())) }
            // Bob keeps priority after casting (CR 117.3b); he passes…
            val bobPostCast = afterCast.pending<DecisionRequest.ChooseAction>()
            bobPostCast.seat shouldBe bob
            val backToAlice = engine.advance(afterCast.pausedState, passDecision(bobPostCast))
            // …and alice's pre-cast pass is forgotten: she gets a window with the spell still up.
            backToAlice.pending<DecisionRequest.ChooseAction>().seat shouldBe alice
            backToAlice.pausedState.sharedZones.stack shouldHaveSize 1
        }

        "CR 601.3e: an invalid decision mid-pipeline aborts with the pre-cast state intact" {
            val start = boltState()
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(start)
            val atTargets = engine.advance(start, castDecision(window, "Fixture Bolt"))
            val targetsRequest = atTargets.pending<DecisionRequest.ChooseTargets>()
            val paused = atTargets.pausedState

            shouldThrow<IllegalArgumentException> {
                engine.advance(paused, Decision.SingleSelect(targetsRequest.id, 99))
            }
            // No half-cast residue: card in hand, stack empty, source untapped, pool empty.
            paused.players.getValue(alice).hand shouldHaveSize 1
            paused.sharedZones.stack.shouldBeEmpty()
            paused.sharedZones.battlefield
                .single()
                .tapped shouldBe false
            paused.players
                .getValue(alice)
                .manaPool
                .shouldBeEmpty()
            // The same paused state still answers correctly afterwards.
            engine
                .advance(paused, targetDecision(targetsRequest, bob))
                .pending<DecisionRequest.ChoosePaymentPlan>()
        }

        "CR 601.3e: an invalid payment decision aborts with the gathered targets intact" {
            val start = boltState()
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(start)
            val atPayment =
                engine
                    .advance(start, castDecision(window, "Fixture Bolt"))
                    .let { engine.advance(it.pausedState, targetDecision(it.pending(), bob)) }
            val paymentRequest = atPayment.pending<DecisionRequest.ChoosePaymentPlan>()
            val paused = atPayment.pausedState

            shouldThrow<IllegalArgumentException> {
                engine.advance(paused, Decision.SingleSelect(paymentRequest.id, 7))
            }
            paused.players.getValue(alice).hand shouldHaveSize 1
            paused.sharedZones.stack.shouldBeEmpty()
            engine.advance(paused, planDecision(paymentRequest)).pending<DecisionRequest.ChooseAction>()
        }

        "CR 704.5a: paying 2 life at 2 life completes the cast, then the state-based action ends the game" {
            val start =
                fixtureState(
                    aliceSetup = SeatSetup(life = 2, hand = listOf("Fixture Gut Punch")),
                    bobSetup = SeatSetup(),
                )
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(start)
            val atPayment = engine.advance(start, castDecision(window, "Fixture Gut Punch"))
            val paymentRequest = atPayment.pending<DecisionRequest.ChoosePaymentPlan>()
            paymentRequest.options shouldHaveSize 1

            val over = engine.advance(atPayment.pausedState, planDecision(paymentRequest))
            val gameOver = over.shouldBeInstanceOf<AdvanceResult.GameOver>()
            gameOver.result shouldBe MatchResult(winner = bob, loser = alice, LossReason.LIFE_TOTAL_ZERO_OR_LESS)
            // The cast completed before the loss: the spell sits on the stack of the final state.
            gameOver.state.sharedZones.stack shouldHaveSize 1
            gameOver.state.events.filterIsInstance<GameEvent.SpellCast>() shouldHaveSize 1
            gameOver.state.events
                .filterIsInstance<GameEvent.LifeChanged>()
                .single()
                .newTotal shouldBe 0
        }

        "CR 107.4: at 2 life with a red source, choosing the mana plan keeps the caster alive" {
            val start =
                fixtureState(
                    aliceSetup =
                        SeatSetup(
                            life = 2,
                            hand = listOf("Fixture Gut Punch"),
                            battlefield = listOf("Fixture Mountain"),
                        ),
                    bobSetup = SeatSetup(),
                )
            val window = pausedRequestOf<DecisionRequest.ChooseAction>(start)
            val atPayment = engine.advance(start, castDecision(window, "Fixture Gut Punch"))
            val paymentRequest = atPayment.pending<DecisionRequest.ChoosePaymentPlan>()
            paymentRequest.options shouldHaveSize 2

            val afterCast = engine.advance(atPayment.pausedState, planDecision(paymentRequest, index = 0))
            afterCast.pausedState.players
                .getValue(alice)
                .life shouldBe 2
            afterCast.pausedState.sharedZones.battlefield
                .single()
                .tapped
                .shouldBeTrue()
        }
    })
