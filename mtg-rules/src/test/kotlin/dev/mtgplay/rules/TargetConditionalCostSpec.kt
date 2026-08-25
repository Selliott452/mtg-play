package dev.mtgplay.rules

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.toPersistentList

/**
 * `FW-TGTCOND` (CR 601.2c, CR 601.2f) — a spell whose total cost is a function of **the target it
 * chooses**, which is Ride's End's "This spell costs `{3}` less to cast if it targets a tapped
 * permanent" and the first cost input in the engine that is not a property of the board.
 *
 * The fixture is "Fixture Lasso", a `{4}{U}` instant with the same clause over plain "target creature".
 * Three properties carry the framework, and each is a different way of getting ADR-005 wrong:
 *
 * 1. the cost the engine charges follows the chosen target (the rule itself);
 * 2. the castability gate must price the **cheapest** achievable choice, or a legal play is silently
 *    missing from the priority window;
 * 3. the target request must then offer only the choices the seat can pay for, or a legal-looking
 *    option dead-ends at an empty `ChoosePaymentPlan`.
 */
class TargetConditionalCostSpec :
    StringSpec({

        "CR 601.2f: targeting a tapped permanent charges the reduced cost" {
            costWhenTargeting(tapped = true) shouldBe "{1}{U}"
        }

        "CR 601.2f: targeting an untapped permanent charges the printed cost" {
            costWhenTargeting(tapped = false) shouldBe "{4}{U}"
        }

        "CR 601.2f: the reduction is confined to the generic component (CR 118.7a) — the pip survives" {
            costWhenTargeting(tapped = true).endsWith("{U}").shouldBeTrue()
        }

        "ADR-005: the cast is enumerated on two lands when a tapped target makes it payable" {
            // {4}{U} is unaffordable on two lands; {1}{U} is not. Pricing the printed cost at the
            // legality gate would delete the whole two-mana line from the action space.
            lassoIsOffered(lands = 2, tapped = true).shouldBeTrue()
        }

        "ADR-005: the same two lands do not enumerate the cast when nothing on the board is tapped" {
            lassoIsOffered(lands = 2, tapped = false).shouldBeFalse()
        }

        "ADR-005: with only the reduced cost payable, only the tapped target is offered" {
            // Both creatures are legal targets under CR 115.1b; only one of them leaves a payable cast,
            // and offering the other would dead-end at an empty payment request (CR 601.2h / CR 728).
            val board = lassoBoard(lands = 2, tappedCreatures = 1, untappedCreatures = 1)
            val options = targetOptionsFor(board)
            options shouldHaveSize 1
            val tappedId =
                board.sharedZones.battlefield
                    .first { it.card == CardRef("Fixture Ox") && it.tapped }
                    .id
            options.single() shouldBe Target.Permanent(tappedId)
        }

        "CR 115.1b: with both costs payable, target legality is untouched — every creature is offered" {
            targetOptionsFor(lassoBoard(lands = 8, tappedCreatures = 1, untappedCreatures = 1)) shouldHaveSize 2
        }

        "CR 601.2f: a card without the clause is unaffected — its options are never filtered" {
            // The affinity fixture prices off the board, not off a target, so nothing here touches it.
            costOf("Fixture Scrapper", artifacts = 2) shouldBe "{3}{U}"
        }
    })

/** The cost the engine charges for Fixture Lasso when its chosen target is (or is not) [tapped]. */
private fun costWhenTargeting(tapped: Boolean): String {
    val board = lassoBoard(lands = 8, tappedCreatures = if (tapped) 1 else 0, untappedCreatures = if (tapped) 0 else 1)
    return paymentRequestFor(board, "Fixture Lasso").cost.render()
}

/** Whether a `PriorityOption.CastSpell` for Fixture Lasso is enumerated on the given board. */
private fun lassoIsOffered(
    lands: Int,
    tapped: Boolean,
): Boolean {
    val board = lassoBoard(lands, tappedCreatures = if (tapped) 1 else 0, untappedCreatures = if (tapped) 0 else 1)
    return pausedRequestOf<DecisionRequest.ChooseAction>(board)
        .options
        .any { it is PriorityOption.CastSpell && it.card == CardRef("Fixture Lasso") }
}

/** The target options the engine offers after choosing to cast Fixture Lasso from [board]. */
private fun targetOptionsFor(board: GameState): List<Target> {
    val window = pausedRequestOf<DecisionRequest.ChooseAction>(board)
    val index =
        window.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == CardRef("Fixture Lasso") }
    check(index >= 0) { "no cast option for Fixture Lasso in ${window.options}" }
    val gathering = DefaultGameEngine().advance(board, Decision.SingleSelect(window.id, index))
    return gathering.pending<DecisionRequest.ChooseTargets>().options
}

/**
 * A board with Fixture Lasso in alice's hand, [lands] blue-capable lands, and bob's creatures split
 * between [tappedCreatures] and [untappedCreatures] — the two axes every case here varies. Bob's
 * creatures rather than alice's so nothing about control enters the picture.
 */
private fun lassoBoard(
    lands: Int,
    tappedCreatures: Int,
    untappedCreatures: Int,
): GameState {
    val board =
        costState(
            alice = SeatSetup(hand = listOf("Fixture Lasso"), battlefield = costLands(lands)),
            bob = SeatSetup(battlefield = List(tappedCreatures + untappedCreatures) { "Fixture Ox" }),
        )
    if (tappedCreatures == 0) return board
    val toTap =
        board.sharedZones.battlefield
            .filter { it.card == CardRef("Fixture Ox") }
            .take(tappedCreatures)
            .map { it.id }
            .toSet()
    return board.copy(
        sharedZones =
            board.sharedZones.copy(
                battlefield =
                    board.sharedZones.battlefield
                        .map { if (it.id in toTap) it.copy(tapped = true) else it }
                        .toPersistentList(),
            ),
    )
}
