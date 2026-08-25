package dev.mtgplay.rules

import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.expandToUnits
import dev.mtgplay.rules.engine.player
import dev.mtgplay.rules.engine.reduceGeneric
import dev.mtgplay.rules.engine.resolveTopOfStack
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * `FW-X` — variable costs (CR 107.3), at the rules level with fixtures (`mtg-rules` names no real
 * card). The framework's central claim is the **bound**: the announceable values of X are exactly the
 * values whose total cost this seat can pay, so an announcement never dead-ends (ADR-005) and no payable
 * value is hidden.
 *
 * Neither gauntlet card with an `{X}` cost could ship — Kaervek's Torch needs a cost increase keyed on
 * another spell's targets, Nyxborn Hydra needs bestow — so these fixtures are the framework's only
 * witnesses and the specs are correspondingly thorough.
 */
class XCostSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        // ---- The bound (CR 107.3b, ADR-005) --------------------------------------------------------

        "CR 107.3b: the announceable values of X are exactly the affordable ones, ascending" {
            // Four Mountains: {X}{R} can pay one red pip and up to three more mana, so X is 0..3.
            val request = xRequestFor("Fixture Surge", lands = List(4) { "Fixture Mountain" }, engine)
            request.values shouldContainExactly listOf(0, 1, 2, 3)
        }

        "CR 107.3b: the bound tracks the board — one more land is one more value" {
            xRequestFor("Fixture Surge", lands = List(5) { "Fixture Mountain" }, engine).values shouldContainExactly
                listOf(0, 1, 2, 3, 4)
        }

        "CR 107.4d: off-colour mana still pays the generic X, so mixed lands widen the bound" {
            // {X}{R} on one Mountain and three Forests: the {R} takes the only red, and the Forests pay
            // the generic X. So X is 0..3 — the Forests count, because generic accepts any type.
            xRequestFor(
                "Fixture Surge",
                lands = listOf("Fixture Mountain") + List(3) { "Fixture Forest" },
                engine,
            ).values shouldContainExactly listOf(0, 1, 2, 3)
        }

        "CR 107.4: the bound is payability, not mana count — no red means no cast at all" {
            // The sharper half of the clause above, and the reason the bound cannot be arithmetic: four
            // Forests are four mana and pay no part of {X}{R}, so the spell is not enumerated and there
            // is no announcement to bound. A "total mana minus the fixed part" rule would have offered
            // X = 0..3 here, every one of them unpayable.
            val state =
                fixtureState(
                    SeatSetup(hand = listOf("Fixture Surge"), battlefield = List(4) { "Fixture Forest" }),
                    SeatSetup(),
                    definitions = optionalCostDefinitions,
                )
            enumeratedCasts(pausedRequestOf<DecisionRequest.ChooseAction>(state)).shouldContainExactly(emptyList())
        }

        "CR 107.3b: a spell whose whole cost is {X} may announce every mana on the board" {
            xRequestFor("Fixture Bolt-X", lands = List(3) { "Fixture Forest" }, engine).values shouldContainExactly
                listOf(0, 1, 2, 3)
        }

        "CR 107.3b: with no mana at all, zero is still an announceable value" {
            xRequestFor("Fixture Bolt-X", lands = emptyList(), engine).values shouldContainExactly listOf(0)
        }

        "ADR-005: the bound is contiguous from zero on a real board — a gap would mean a lost play" {
            // The engine does *not* assume monotonicity (each candidate is tested independently), so this
            // is an assertion about the boards, not about the algorithm. If a future mana source makes it
            // false, this fails and the packet report's reasoning gets revisited rather than a legal
            // announcement silently disappearing.
            listOf(1, 2, 3, 4, 5).forEach { landCount ->
                val values = xRequestFor("Fixture Bolt-X", List(landCount) { "Fixture Forest" }, engine).values
                values shouldContainExactly (0..landCount).toList()
            }
        }

        // ---- The announcement's position and effect (CR 601.2b) ------------------------------------

        "CR 601.2b: X is announced before the payment plan, and the plan pays the announced cost" {
            val paused = xGathering("Fixture Surge", List(4) { "Fixture Mountain" }, engine)
            val request = pausedRequestOf<DecisionRequest.ChooseXValue>(paused)
            // Announce X = 2: the total cost becomes {2}{R}.
            val afterX = engine.advance(paused, Decision.SingleSelect(request.id, request.values.indexOf(2)))
            val plan = afterX.pending<DecisionRequest.ChoosePaymentPlan>()
            plan.cost shouldBe ManaCost.parse("{2}{R}")
        }

        "CR 601.2f: announcing zero leaves the coloured remainder, with no dead {0} symbol" {
            val paused = xGathering("Fixture Surge", List(4) { "Fixture Mountain" }, engine)
            val request = pausedRequestOf<DecisionRequest.ChooseXValue>(paused)
            val afterX = engine.advance(paused, Decision.SingleSelect(request.id, request.values.indexOf(0)))
            afterX.pending<DecisionRequest.ChoosePaymentPlan>().cost shouldBe ManaCost.parse("{R}")
        }

        "CR 202.3b: the announced value rides on the cast record, not on the printed cost" {
            val state = castSurgeFor(x = 2, lands = List(4) { "Fixture Mountain" }, engine)
            val entry = state.sharedZones.stack.single() as StackEntry.Spell
            entry.chosenX shouldBe 2
            // The printed cost is untouched: {X} is still {X}, and its mana value is still just the {R}.
            entry.definition.manaCost shouldBe ManaCost.parse("{X}{R}")
            entry.definition.characteristics.manaValue shouldBe 1
        }

        "CR 202.3b: a spell on the stack has mana value X + the rest; the card elsewhere has X = 0" {
            val state = castSurgeFor(x = 3, lands = List(5) { "Fixture Mountain" }, engine)
            val entry = state.sharedZones.stack.single() as StackEntry.Spell
            // On the stack: 3 + 1.
            spellManaValueOfEntry(state, entry) shouldBe 4
        }

        "CR 601.2b: the announced value is what the resolution reads" {
            val state = castSurgeFor(x = 3, lands = List(5) { "Fixture Mountain" }, engine)
            val resolved = resolveTopOfStack(state).pausedState
            // Fixture Surge deals X to the targeted player; alice targeted herself in the helper.
            resolved.player(alice).life shouldBe STARTING_LIFE - 3
        }

        // ---- The fail-loud guarantee (ADR-005) -----------------------------------------------------

        "CR 601.2b: an unannounced {X} has no payable expansion and fails loudly" {
            val failure = shouldThrow<IllegalStateException> { expandToUnits(ManaCost.parse("{X}{R}")) }
            failure.message.shouldContain("CR 601.2b")
            failure.message.shouldContain("{X}")
        }

        "CR 601.2b: a cost still carrying {X} cannot be reduced either" {
            // The other half of the same guarantee: the reduction path refuses it too, so a call site
            // that skipped the substitution cannot under-price the spell instead of crashing.
            shouldThrow<IllegalStateException> { reduceGeneric(ManaCost.parse("{X}{2}{R}"), 1) }
        }

        // ---- Enumeration completeness (ADR-005) ----------------------------------------------------

        "ADR-005: an {X} spell is enumerated whenever X = 0 is payable, and not otherwise" {
            // One Mountain pays {X}{R} with X = 0, so the cast is offered.
            val castable =
                fixtureState(
                    SeatSetup(hand = listOf("Fixture Surge"), battlefield = listOf("Fixture Mountain")),
                    SeatSetup(),
                    definitions = optionalCostDefinitions,
                )
            enumeratedCasts(pausedRequestOf<DecisionRequest.ChooseAction>(castable))
                .shouldContainExactly(listOf("Fixture Surge"))

            // A lone Forest cannot pay the {R} at any value of X, so it is absent entirely.
            val uncastable =
                fixtureState(
                    SeatSetup(hand = listOf("Fixture Surge"), battlefield = listOf("Fixture Forest")),
                    SeatSetup(),
                    definitions = optionalCostDefinitions,
                )
            enumeratedCasts(pausedRequestOf<DecisionRequest.ChooseAction>(uncastable)).shouldContainExactly(emptyList())
        }

        "ADR-005: every announceable value executes through the whole pipeline without dead-ending" {
            // The bound's contract, asserted directly rather than inferred: take each offered value in
            // turn and drive the cast to completion. A value that could not be paid would throw here.
            val lands = List(4) { "Fixture Mountain" }
            val values = xRequestFor("Fixture Surge", lands, engine).values
            values.forEach { value ->
                val state = castSurgeFor(value, lands, engine)
                (state.sharedZones.stack.single() as StackEntry.Spell).chosenX shouldBe value
            }
        }

        // ---- Kicker and X together (CR 601.2b) -----------------------------------------------------

        "CR 601.2b: kicker is announced before X, and the kicker narrows the affordable values" {
            val lands = List(5) { "Fixture Mountain" }
            val paused = beginCast("Fixture Kicked Surge", lands, engine)
            // The kicker announcement comes first.
            val kicker = pausedRequestOf<DecisionRequest.ChooseYesNo>(paused)
            kicker.card.name shouldBe "Fixture Kicked Surge"

            // Declining leaves {X}{R} against five Mountains: X is 0..4.
            val unkicked = engine.advance(paused, Decision.SingleSelect(kicker.id, DecisionRequest.ChooseYesNo.DECLINE))
            unkicked.pending<DecisionRequest.ChooseXValue>().values shouldContainExactly listOf(0, 1, 2, 3, 4)

            // Accepting adds {2}, so the same board affords two fewer values of X.
            val kicked = engine.advance(paused, Decision.SingleSelect(kicker.id, DecisionRequest.ChooseYesNo.ACCEPT))
            kicked.pending<DecisionRequest.ChooseXValue>().values shouldContainExactly listOf(0, 1, 2)
        }
    })
