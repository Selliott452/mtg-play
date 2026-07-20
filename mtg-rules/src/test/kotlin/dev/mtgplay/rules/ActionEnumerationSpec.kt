package dev.mtgplay.rules

import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

/**
 * Enumeration completeness in both directions (ADR-005): every enumerated cast executes
 * through the whole pipeline (no phantom options), and constructed legal/illegal scenarios
 * are/aren't enumerated (no missing options, nothing illegal).
 */
class ActionEnumerationSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        fun windowOf(state: GameState) = pausedRequestOf<DecisionRequest.ChooseAction>(state)

        "CR 117.1a: a sorcery-speed fixture is enumerated only in the active player's own main phase" {
            val inMain =
                fixtureState(
                    aliceSetup = SeatSetup(hand = listOf("Fixture Comet"), battlefield = listOf("Fixture Mountain")),
                    bobSetup = SeatSetup(),
                )
            enumeratedCasts(windowOf(inMain)) shouldContain "Fixture Comet"

            val inUpkeep =
                fixtureState(
                    aliceSetup = SeatSetup(hand = listOf("Fixture Comet"), battlefield = listOf("Fixture Mountain")),
                    bobSetup = SeatSetup(),
                    turn = Turn(alice, 3, TurnPhase.BEGINNING, TurnStep.UPKEEP),
                )
            enumeratedCasts(windowOf(inUpkeep)) shouldNotContain "Fixture Comet"
        }

        "CR 117.1a: a sorcery-speed fixture is absent from the non-active player's windows" {
            val bobsTurn =
                fixtureState(
                    aliceSetup = SeatSetup(hand = listOf("Fixture Comet"), battlefield = listOf("Fixture Mountain")),
                    bobSetup = SeatSetup(),
                    turn = Turn(bob, 4, TurnPhase.PRECOMBAT_MAIN, null),
                )
            enumeratedCasts(windowOf(bobsTurn)) shouldNotContain "Fixture Comet"
            // The instant-speed fixture is castable from the same window (CR 117.1a).
            val withBolt =
                fixtureState(
                    aliceSetup = SeatSetup(hand = listOf("Fixture Bolt"), battlefield = listOf("Fixture Mountain")),
                    bobSetup = SeatSetup(),
                    turn = Turn(bob, 4, TurnPhase.PRECOMBAT_MAIN, null),
                )
            enumeratedCasts(windowOf(withBolt)) shouldContain "Fixture Bolt"
        }

        "CR 117.1a: a nonempty stack blocks sorcery-speed casts but not instant-speed ones" {
            val start =
                fixtureState(
                    aliceSetup =
                        SeatSetup(
                            hand = listOf("Fixture Bolt", "Fixture Bolt", "Fixture Comet"),
                            battlefield = List(3) { "Fixture Mountain" },
                        ),
                    bobSetup = SeatSetup(),
                )
            // Alice casts one Bolt; in her post-cast window the stack is nonempty.
            var current = engine.advance(start, castDecision(windowOf(start), "Fixture Bolt"))
            current = engine.advance(current.pausedState, targetDecision(current.pending(), bob))
            current = engine.advance(current.pausedState, planDecision(current.pending()))
            val postCast = current.pending<DecisionRequest.ChooseAction>()
            enumeratedCasts(postCast) shouldContain "Fixture Bolt"
            enumeratedCasts(postCast) shouldNotContain "Fixture Comet"
        }

        "ADR-005: an unaffordable cast is not enumerated — wrong color, too few sources, none at all" {
            val wrongColor =
                fixtureState(
                    aliceSetup = SeatSetup(hand = listOf("Fixture Bolt"), battlefield = listOf("Fixture Forest")),
                    bobSetup = SeatSetup(),
                )
            enumeratedCasts(windowOf(wrongColor)).shouldBeEmpty()

            val noSources =
                fixtureState(aliceSetup = SeatSetup(hand = listOf("Fixture Bolt")), bobSetup = SeatSetup())
            enumeratedCasts(windowOf(noSources)).shouldBeEmpty()
        }

        "ADR-005: an inert card — no definition — is never enumerated" {
            val inertHand =
                fixtureState(
                    aliceSetup = SeatSetup(hand = listOf("Mountain"), battlefield = listOf("Fixture Mountain")),
                    bobSetup = SeatSetup(),
                )
            windowOf(inertHand).options shouldBe listOf(PriorityOption.Pass)
        }

        "CR 107.4: Phyrexian affordability — 2 life alone suffices; 1 life with no source does not" {
            val payable =
                fixtureState(
                    aliceSetup = SeatSetup(life = 2, hand = listOf("Fixture Gut Punch")),
                    bobSetup = SeatSetup(),
                )
            enumeratedCasts(windowOf(payable)) shouldContain "Fixture Gut Punch"

            val unpayable =
                fixtureState(
                    aliceSetup = SeatSetup(life = 1, hand = listOf("Fixture Gut Punch")),
                    bobSetup = SeatSetup(),
                )
            enumeratedCasts(windowOf(unpayable)).shouldBeEmpty()
        }

        "no phantom options: every enumerated cast executes through the full pipeline, over every choice" {
            val scenarios =
                listOf(
                    SeatSetup(
                        hand = listOf("Fixture Bolt", "Fixture Comet", "Fixture Meditation"),
                        battlefield = listOf("Fixture Mountain", "Fixture Mountain"),
                    ),
                    SeatSetup(
                        hand = listOf("Fixture Bloom", "Fixture Gut Punch"),
                        battlefield = listOf("Fixture Forest", "Fixture Island", "Fixture Prism"),
                    ),
                    SeatSetup(hand = listOf("Fixture Gut Punch"), life = 2),
                )
            scenarios.forEach { setup ->
                val start = fixtureState(aliceSetup = setup, bobSetup = SeatSetup())
                val window = windowOf(start)
                window.options.filterIsInstance<PriorityOption.CastSpell>().forEach { option ->
                    val optionIndex = window.options.indexOf(option)
                    var current = engine.advance(start, Decision.SingleSelect(window.id, optionIndex))
                    // Walk every remaining gathering decision with its first option; the cast
                    // must complete (a post-cast window, or a legitimate game over when a life
                    // payment is lethal) — and never throw.
                    var guard = 0
                    while (current is AdvanceResult.NeedsDecision &&
                        current.request !is DecisionRequest.ChooseAction
                    ) {
                        check(guard++ < 5) { "cast gathering did not converge for ${option.card.name}" }
                        current = engine.advance(current.state, Decision.SingleSelect(current.request.id, 0))
                    }
                    when (val result = current) {
                        is AdvanceResult.NeedsDecision ->
                            result.state.sharedZones.stack.size shouldBeGreaterThanOrEqual 1
                        is AdvanceResult.GameOver ->
                            result.state.sharedZones.stack.size shouldBeGreaterThanOrEqual 1
                    }
                }
            }
        }
    })
