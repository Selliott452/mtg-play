package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.definition.CountScope
import dev.mtgplay.core.definition.ObjectPredicate
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaSymbol
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.countMatching
import dev.mtgplay.rules.engine.reduceGeneric
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf

/**
 * The CR 601.2f cost-modification framework (docs/design/cost-modification.md): the counting primitive,
 * the reduction arithmetic, the **lock-in**, and — the headline property — that the three places a
 * spell is priced all agree.
 *
 * The design note names cost *divergence* as the framework's only way to manufacture an ADR-005 silent
 * defect: an option enumerated against one cost whose `ChoosePaymentPlan` is derived against another,
 * and paid against a third. Everything else here is arithmetic that fails loudly when wrong; that one
 * fails quietly, so it gets the most direct test in the file.
 */
class CostModificationSpec :
    StringSpec({

        // ---- C1: the counting primitive ----------------------------------------------------------

        "CR 403: a battlefield count matches only the counting player's own permanents" {
            val state =
                costState(
                    alice = SeatSetup(battlefield = listOf("Fixture Relic", "Fixture Relic")),
                    bob = SeatSetup(battlefield = listOf("Fixture Relic")),
                )
            countMatching(state, alice, CountScope.BATTLEFIELD_YOU_CONTROL, artifacts) shouldBe 2
            countMatching(state, bob, CountScope.BATTLEFIELD_YOU_CONTROL, artifacts) shouldBe 1
        }

        "CR 404: a graveyard count reads card types, and an unmatched type is not counted" {
            val state =
                costState(
                    alice =
                        SeatSetup(
                            graveyard = listOf("Fixture Rite", "Fixture Spark", "Fixture Stone"),
                        ),
                )
            // Two of the three are an instant or a sorcery; the enchantment is not.
            countMatching(state, alice, CountScope.YOUR_GRAVEYARD, instantsAndSorceries) shouldBe 2
        }

        "CR 205.3: a negated subtype predicate counts exactly the objects the positive one does not" {
            val state = costState(alice = SeatSetup(battlefield = listOf("Fixture Relic", "Fixture Atoll")))
            val relics = ObjectPredicate.HasSubtype(FIXTURE_RELIC_TYPE)
            val notRelics = ObjectPredicate.Not(relics)
            countMatching(state, alice, CountScope.BATTLEFIELD_YOU_CONTROL, relics) shouldBe 1
            countMatching(state, alice, CountScope.BATTLEFIELD_YOU_CONTROL, notRelics) shouldBe 1
        }

        "P2.1: an inert card with no definition matches no predicate rather than counting as anything" {
            // "Mountain" has no fixture definition in this registry.
            val state = costState(alice = SeatSetup(graveyard = listOf("Mountain")))
            countMatching(state, alice, CountScope.YOUR_GRAVEYARD, ObjectPredicate.Anything) shouldBe 0
        }

        // ---- C3: the reduction arithmetic --------------------------------------------------------

        "CR 118.7a: a generic reduction cannot touch a coloured pip, so {5}{U}{U} floors at {U}{U}" {
            val cost = ManaCost.parse("{5}{U}{U}")
            reduceGeneric(cost, 5).render() shouldBe "{U}{U}"
            // Seven instants and sorceries in the graveyard, and it is still {U}{U}.
            reduceGeneric(cost, 7).render() shouldBe "{U}{U}"
            reduceGeneric(cost, 99).render() shouldBe "{U}{U}"
        }

        "CR 118.7a: a colorless {C} pip is not generic and survives reduction too" {
            reduceGeneric(ManaCost.parse("{2}{C}"), 5).render() shouldBe "{C}"
        }

        "CR 601.2f: a mana component reduced to nothing is {0}, and cannot go below it" {
            reduceGeneric(ManaCost.parse("{7}"), 7).render() shouldBe "{0}"
            reduceGeneric(ManaCost.parse("{7}"), 9).render() shouldBe "{0}"
        }

        "CR 601.2f: a zeroed generic symbol is dropped rather than rendered as a dead {0}" {
            // {0}{U}{U} would pay identically but read as a defect in the CLI menu and on the wire.
            reduceGeneric(ManaCost.parse("{5}{U}{U}"), 5).render() shouldBe "{U}{U}"
            reduceGeneric(ManaCost.parse("{4}{U}"), 4).render() shouldBe "{U}"
        }

        "CR 601.2f: applying reductions in any order yields the same cost, which is why no decision is surfaced" {
            // The CR grants the player a free choice of order; with generic-only reductions the result
            // is max(0, generic - sum), which commutes. This property is what justifies ADR-005
            // enumerating no option for that freedom — no legal outcome is missing.
            val cost = ManaCost.parse("{5}{U}{U}")
            val parts = listOf(1, 2, 3, 4)
            val orders = permutations(parts)
            val results = orders.map { order -> order.fold(cost) { acc, n -> reduceGeneric(acc, n) }.render() }
            withClue("orders: $orders -> $results") { results.distinct().size shouldBe 1 }
            // And the same as applying the sum in one step.
            results.first() shouldBe reduceGeneric(cost, parts.sum()).render()
        }

        "CR 601.2f: a reduction of zero leaves the cost identical, including its symbol shape" {
            reduceGeneric(ManaCost.parse("{5}{U}{U}"), 0).render() shouldBe "{5}{U}{U}"
        }

        // ---- C4/C6: the two declaration shapes price correctly ------------------------------------

        "CR 702.41a: affinity reduces by one per artifact and the spell being cast never counts itself" {
            // Fixture Scrapper is itself an artifact creature. In hand it is in no counted zone, and
            // on the stack it has left the battlefield-count's zone — so it is never among its own
            // artifacts, at either the gathering or the execution read.
            costOf("Fixture Scrapper", artifacts = 0) shouldBe "{5}{U}"
            costOf("Fixture Scrapper", artifacts = 3) shouldBe "{2}{U}"
            costOf("Fixture Scrapper", artifacts = 5) shouldBe "{U}"
            costOf("Fixture Scrapper", artifacts = 9) shouldBe "{U}"
        }

        "CR 601.2f: a conditional flat reduction is all-or-nothing, not a count" {
            costOf("Fixture Accord", artifacts = 0) shouldBe "{3}{U}"
            // One short of the threshold: still nothing.
            costOf("Fixture Accord", artifacts = 1) shouldBe "{3}{U}"
            costOf("Fixture Accord", artifacts = 2) shouldBe "{1}{U}"
            // Over the threshold it does not keep growing — that is the difference from affinity.
            costOf("Fixture Accord", artifacts = 6) shouldBe "{1}{U}"
        }

        "CR 604.5: a battlefield permanent reduces its controller's matching spells, once per reducer" {
            // Fixture Warden is white and reduces blue: a reducer that shares no colour with what it
            // reduces, so nothing here can pass by matching the reducer's own colour.
            costOfBoard("Fixture Leviathan", listOf()) shouldBe "{4}{U}"
            costOfBoard("Fixture Leviathan", listOf("Fixture Warden")) shouldBe "{3}{U}"
            costOfBoard("Fixture Leviathan", listOf("Fixture Warden", "Fixture Warden")) shouldBe "{2}{U}"
        }

        "CR 613.11: an opponent's reducer does not reduce your spells" {
            val state =
                costState(
                    alice = SeatSetup(hand = listOf("Fixture Leviathan"), battlefield = costLands(LANDS_FOR_COST_READ)),
                    bob = SeatSetup(battlefield = listOf("Fixture Warden")),
                )
            determinedCost(state, "Fixture Leviathan") shouldBe "{4}{U}"
        }

        "CR 404: a graveyard-counting reduction counts cards, not the zone's size" {
            costOfGraveyard("Fixture Leviathan", listOf()) shouldBe "{4}{U}"
            costOfGraveyard("Fixture Leviathan", listOf("Fixture Rite", "Fixture Spark")) shouldBe "{2}{U}"
            // Three enchantments in the graveyard change nothing: the predicate is on card type.
            costOfGraveyard(
                "Fixture Leviathan",
                listOf("Fixture Stone", "Fixture Stone", "Fixture Stone"),
            ) shouldBe "{4}{U}"
        }

        // ---- C2: the headline property — the three pricing sites agree ---------------------------

        "ADR-005: legality, request derivation and execution price a modified cast identically" {
            // The framework's only silent failure mode. Walked over a range of boards so a divergence
            // that only shows at one reduction amount cannot hide.
            (0..6).forEach { artifacts ->
                val state = affinityBoard(artifacts)
                val enumerated = enumeratedCasts(pausedRequestOf<DecisionRequest.ChooseAction>(state))
                withClue("$artifacts artifacts: enumerated $enumerated") {
                    enumerated shouldContain "Fixture Scrapper"
                }
                // The request derivation's cost, and the plans it offers, must match what the
                // pipeline then pays — which is what `validatePlanShape` checks as the plan executes.
                val request = paymentRequestFor(state, "Fixture Scrapper")
                val expected = reduceGeneric(ManaCost.parse("{5}{U}"), artifacts)
                withClue("$artifacts artifacts") {
                    request.cost shouldBe expected
                    // Every enumerated plan pays exactly the determined cost's expanded symbols.
                    request.options.forEach { it.payments.size shouldBe unitCount(expected) }
                }
            }
        }

        "ADR-005: a cast legal only because of its reduction is enumerated, and illegal without it is not" {
            // Five lands: {5}{U} is unpayable, {2}{U} is payable. Three artifacts bridge the gap, and
            // the artifacts are themselves the lands' peers on the battlefield rather than extra mana.
            val withoutReduction = affinityBoard(artifacts = 0, lands = 3)
            enumeratedCasts(
                pausedRequestOf<DecisionRequest.ChooseAction>(withoutReduction),
            ) shouldNotContain "Fixture Scrapper"

            val withReduction = affinityBoard(artifacts = 3, lands = 3)
            enumeratedCasts(
                pausedRequestOf<DecisionRequest.ChooseAction>(withReduction),
            ) shouldContain "Fixture Scrapper"
        }

        "CR 601.2f: a cost reduced to {0} still surfaces a payment request, with exactly one plan" {
            // ChoosePaymentPlan.init requires a non-empty option set, and {0} expands to zero units,
            // so the single option is the empty plan. No auto-pass: replay logs stay canonical (P2.1).
            val state = zeroCostBoard()
            val request = paymentRequestFor(state, "Fixture Colossus")
            request.cost.render() shouldBe "{0}"
            request.options.size shouldBe 1
            request.options
                .single()
                .payments
                .shouldBeEmptyPlan()
        }

        // ---- C2: lock-in, one test per cost-payment stage -----------------------------------------

        "CR 601.2f: the total cost is locked in before a sacrifice cost removes a counted artifact" {
            // The CR 601.2h example, in this engine: "Because a spell's total cost is 'locked in'
            // before payments are actually made, you pay {B}, not {1}{B}, even though you're
            // sacrificing the Familiar." Fixture Tithe has affinity and sacrifices a Fixture Relic.
            // Three relics out: the cost is determined at {1}{U} and the sacrifice must not re-price
            // it to {2}{U}. Before this packet, determination ran *after* the sacrifice stage.
            val state =
                costState(
                    alice =
                        SeatSetup(
                            hand = listOf("Fixture Tithe"),
                            battlefield = listOf("Fixture Relic", "Fixture Relic", "Fixture Relic") + costLands(2),
                        ),
                )
            val request = paymentRequestFor(state, "Fixture Tithe", permission = true)
            // {4}{U} minus three artifacts, all three still on the battlefield when the cost is fixed.
            request.cost.render() shouldBe "{1}{U}"
            // And the cast completes paying that, rather than throwing in validatePlanShape.
            completes(state, "Fixture Tithe", permission = true) shouldBe true
        }

        "CR 601.2f: the total cost is locked in before an additional discard reaches a counted graveyard" {
            // Fixture Leviathan counts instants and sorceries in the graveyard. If the additional
            // discard stage ran first, discarding a Fixture Rite would make the spell one cheaper than
            // the cost its ChoosePaymentPlan was derived against.
            val state = discardCostBoard()
            val request = paymentRequestFor(state, "Fixture Reckoning")
            // One sorcery already in the graveyard; the card about to be discarded is still in hand.
            request.cost.render() shouldBe "{3}{U}"
            completes(state, "Fixture Reckoning") shouldBe true
        }

        "CR 601.2a: a graveyard-cast spell does not count itself among the cards in its graveyard" {
            // The one place the gathering-time and execution-time reads could differ by exactly one:
            // while gathering, the card is still in the graveyard it counts. Excluding the cast object
            // is what makes the two answers equal by construction rather than by stage placement.
            val state = flashbackBoard()
            val request = paymentRequestFor(state, "Fixture Recall", permission = true)
            // The graveyard holds three sorceries/instants, one of which *is* the Recall being cast.
            // Counting the two others reduces {3}{U} to {1}{U}; counting itself as well would give
            // {U}, so the two answers are distinguishable and this pins the excluding one.
            request.cost.render() shouldBe "{1}{U}"
            completes(state, "Fixture Recall", permission = true) shouldBe true
        }
    })

/** The artifact predicate the affinity fixtures declare. */
private val artifacts = ObjectPredicate.HasCardType(CardType.ARTIFACT)

/** The "instant and sorcery card" disjunction the Terrors' clause states (CR 205.2a). */
private val instantsAndSorceries =
    ObjectPredicate.AnyOf(
        persistentListOf(
            ObjectPredicate.HasCardType(CardType.INSTANT),
            ObjectPredicate.HasCardType(CardType.SORCERY),
        ),
    )

/** Every permutation of [items] — small enough to enumerate exhaustively for the order property. */
private fun permutations(items: List<Int>): List<List<Int>> =
    if (items.size <= 1) {
        listOf(items)
    } else {
        items.flatMap { head -> permutations(items - head).map { listOf(head) + it } }
    }

/** How many payment units a cost expands to — `{0}` expands to none (CR 601.2g). */
private fun unitCount(cost: ManaCost): Int = cost.symbols.sumOf { if (it is ManaSymbol.Generic) it.amount else 1 }

/** Asserts a plan pays nothing — the single option a `{0}` cost enumerates. */
private fun List<*>.shouldBeEmptyPlan() {
    withClue("a {0} cost is paid by the empty plan") { size shouldBe 0 }
}
