package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.RandomLegalResponder
import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Land-drop legality on real cards (CR 305.1, CR 305.2): the play-land option is enumerated
 * *exactly* when the special action is legal — asserted both by targeted scripted probes and
 * by auditing every priority window of a random corpus against an independently re-derived
 * legality oracle (ADR-005: enumeration completeness in both directions).
 */
class LandDropAcceptanceSpec :
    StringSpec({

        fun hasPlayLand(request: DecisionRequest.ChooseAction): Boolean =
            request.options.any { it is PriorityOption.PlayLand }

        // Whether the CR 305 special action is legal for the deciding seat of [request] in
        // [state] — re-derived from the CR text, independently of the engine's own predicate.
        fun playLandLegalOracle(
            request: DecisionRequest.ChooseAction,
            state: GameState,
        ): Boolean {
            val mainPhase =
                state.turn.phase == TurnPhase.PRECOMBAT_MAIN || state.turn.phase == TurnPhase.POSTCOMBAT_MAIN
            val landInHand =
                state.players.getValue(request.seat).hand.any { obj ->
                    val definition = MvpCards.definitions[obj.card]
                    definition != null && CardType.LAND in definition.characteristics.cardTypes
                }
            return request.seat == state.turn.activePlayer &&
                // CR 305.1: own turn only
                mainPhase &&
                // CR 305.1: a main phase
                state.sharedZones.stack.isEmpty() &&
                // CR 116.2a: stack empty
                state.turn.landsPlayedThisTurn == 0 &&
                // CR 305.2: one land per turn
                landInHand
        }

        "CR 305.2: the second land play is not enumerated the same turn, and returns next turn" {
            val seed =
                seedWithOpeningHand(alice, { s -> mountainConfig(s, startingPlayer = alice) }) { hand ->
                    hand.count { it == "Mountain" } >= 2
                }
            val game = ScriptedGame.start(mountainConfig(seed, startingPlayer = alice))
            game.passUntil { it.turn.phase == TurnPhase.PRECOMBAT_MAIN }

            val main = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            hasPlayLand(main).shouldBeTrue()
            val playIndex = main.options.indexOfFirst { it is PriorityOption.PlayLand }
            game.apply(Decision.SingleSelect(main.id, playIndex))

            // Same turn, retained priority (CR 116.4), a second Mountain in hand — no option.
            val after = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            after.seat shouldBe alice
            game.state.turn.landsPlayedThisTurn shouldBe 1
            hasPlayLand(after).shouldBeFalse()

            // The drop is fresh again on alice's next turn (turn 3).
            game.passUntil {
                it.turn.number == 3 && it.turn.phase == TurnPhase.PRECOMBAT_MAIN
            }
            game.state.turn.landsPlayedThisTurn shouldBe 0
            hasPlayLand(game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()).shouldBeTrue()
        }

        "CR 305.1: not enumerated outside a main phase, nor for the non-active player" {
            val game = ScriptedGame.start(mountainConfig(startingPlayer = alice))
            // The game's first window is alice's upkeep — no main phase, no option, though her
            // lands-only opening hand is all Mountains.
            val upkeepWindow = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            game.state.turn.phase shouldBe TurnPhase.BEGINNING
            hasPlayLand(upkeepWindow).shouldBeFalse()

            // In alice's precombat main, alice passes; bob's window opens in *alice's* main —
            // bob's hand is also all Mountains, and the option must still be absent (CR 305.1:
            // only during their own turn).
            game.passUntil { it.turn.phase == TurnPhase.PRECOMBAT_MAIN }
            game.pass()
            val bobWindow = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            bobWindow.seat shouldBe bob
            game.state.turn.activePlayer shouldBe alice
            hasPlayLand(bobWindow).shouldBeFalse()
        }

        "ADR-005 both directions: across a random corpus, play-land is enumerated exactly when legal" {
            var enumerated = 0
            var suppressed = 0
            (0L until 8L).forEach { seed ->
                val game =
                    ScriptedGame
                        .start(burnConfig(seed, bolts = 20))
                        .playToCompletion(RandomLegalResponder(seed), turnCap = REAL_CARD_TURN_CAP)
                game.pauses.forEach { pause ->
                    val request = pause.request
                    if (request is DecisionRequest.ChooseAction) {
                        val expected = playLandLegalOracle(request, pause.state)
                        hasPlayLand(request) shouldBe expected
                        if (expected) enumerated += 1 else suppressed += 1
                    }
                }
            }
            // The corpus actually exercised both directions of the property.
            enumerated shouldBeGreaterThan 0
            suppressed shouldBeGreaterThan 0
        }

        "CR 305.2 executes: a random corpus never plays two lands in one turn" {
            (0L until 4L).forEach { seed ->
                val game =
                    ScriptedGame
                        .start(mountainConfig(seed, startingPlayer = null))
                        .playToCompletion(RandomLegalResponder(seed), turnCap = REAL_CARD_TURN_CAP)
                game.pauses.forEach { pause ->
                    pause.state.turn.landsPlayedThisTurn shouldBeGreaterThanOrEqual 0
                    (pause.state.turn.landsPlayedThisTurn <= 1).shouldBeTrue()
                }
            }
        }
    })
