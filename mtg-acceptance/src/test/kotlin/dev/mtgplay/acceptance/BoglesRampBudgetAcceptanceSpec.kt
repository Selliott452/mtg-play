package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.decision.PriorityOption
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The size and the shape of the payment action space a GW Bogles agent actually sees, pinned on one
 * realistic mid-game board (docs/design/mana-payment.md §4). Two things are asserted, and the second
 * is the point of the P8.3 packet:
 *
 * - the **option count** per cost, so a future change to the enumerator that inflates the action
 *   space fails here rather than quietly making the environment harder to consume (ADR-005);
 * - the **fewest lands a plan needs**, which is strictly lower than the number of mana symbols for
 *   every cost the ramp Auras can carry. Before P8.3 a plan activated exactly one mana ability per
 *   symbol, so on an empty pool that number was always the symbol count; a plan that pays `{1}{G}`
 *   off a single Utopia-Sprawl'd Forest was unrepresentable.
 *
 * The board is the deck's own: four ramp Auras' worth of mana on six lands — two Utopia Sprawl'd
 * Forests (green chosen), a Wild-Growth'd Forest, an Abundant-Growth'd Forest (any colour, a layer-6
 * grant rather than a CR 605.1b bonus), a bare Forest and a Plains.
 */
class BoglesRampBudgetAcceptanceSpec :
    StringSpec({

        "docs/design/mana-payment.md §4: the enumerated payment options on a realistic GW Bogles board" {
            // Pinned counts. They are not a correctness property on their own — PaymentEnumerationSpec's
            // oracle owns completeness — but an unexplained move in either direction is worth a look.
            plansFor("Rancor") shouldHaveExactly 3
            plansFor("Malevolent Rumble") shouldHaveExactly 16
            plansFor("Ancestral Mask") shouldHaveExactly 32
            plansFor("Kruphix's Insight") shouldHaveExactly 32
            plansFor("Armadillo Cloak") shouldHaveExactly 16
        }

        "CR 605.1b: on that board every multi-symbol cost has a plan that taps fewer lands than it pays mana" {
            listOf("Malevolent Rumble", "Ancestral Mask", "Kruphix's Insight", "Armadillo Cloak").forEach { card ->
                val plans = plansFor(card)
                val symbols = plans.first().payments.size
                // The pool is empty, so before P8.3 every plan activated exactly one source per symbol.
                plans.minOf { it.activations.size } shouldBeLessThan symbols
            }
        }

        "CR 605.1b: a two-mana spell is payable off a single ramp-enchanted land on that board" {
            val single = plansFor("Malevolent Rumble").filter { it.activations.size == 1 }
            single.isNotEmpty() shouldBe true
            // The one activation is of a class carrying a triggered-mana bonus: that is where the
            // second mana comes from.
            single.forEach { plan ->
                plan.activations
                    .single()
                    .sourceClass.bonus
                    .size shouldBe 1
            }
        }
    })

/** Asserts a plan list's size, with the list rendered on failure so a move is diagnosable. */
private infix fun List<PaymentPlan>.shouldHaveExactly(expected: Int) {
    withClue(joinToString("\n")) { size shouldBe expected }
}

/**
 * The plans the engine enumerates for casting [card] from alice's hand on [boglesRampBoard] — the
 * `ChoosePaymentPlan` options an agent would be handed (ADR-005), reached through a real cast.
 */
private fun plansFor(card: String): List<PaymentPlan> {
    val game = ScriptedGame.startFrom(boglesRampBoard(card))
    val window = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
    val castIndex = window.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == CardRef(card) }
    check(castIndex >= 0) { "no CastSpell option for $card in ${window.options}" }
    game.apply(Decision.SingleSelect(window.id, castIndex))
    if (game.pendingRequest is DecisionRequest.ChooseTargets) {
        val targets = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
        val creature = targets.options.indexOf(Target.Permanent(ObjectId(RAMP_BOARD_CREATURE)))
        check(creature >= 0) { "no enchantable creature for $card in ${targets.options}" }
        game.apply(Decision.SingleSelect(targets.id, creature))
    }
    return game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>().options
}

/** The Aura target on the pinned board: a Gladecover Scout that has been out since the turn began. */
private const val RAMP_BOARD_CREATURE: Long = 20

/** The turn the pinned board resumes on — late enough that nothing is summoning sick. */
private const val RAMP_BOARD_TURN: Int = 5

private fun rampObj(
    id: Long,
    name: String,
): GameObject = GameObject(ObjectId(id), CardRef(name), alice)

/** The pinned mid-game GW Bogles board, with [inHand] the one card in alice's hand. */
private fun boglesRampBoard(inHand: String): GameState {
    val battlefield =
        listOf(
            rampObj(0, "Forest"),
            rampObj(1, "Utopia Sprawl").copy(attachedTo = ObjectId(0), chosenColor = Color.GREEN),
            rampObj(2, "Forest"),
            rampObj(3, "Utopia Sprawl").copy(attachedTo = ObjectId(2), chosenColor = Color.GREEN),
            rampObj(4, "Forest"),
            rampObj(5, "Wild Growth").copy(attachedTo = ObjectId(4)),
            rampObj(6, "Forest"),
            rampObj(7, "Abundant Growth").copy(attachedTo = ObjectId(6)),
            rampObj(8, "Forest"),
            rampObj(9, "Plains"),
            rampObj(RAMP_BOARD_CREATURE, "Gladecover Scout").copy(summoningSick = false),
        )
    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = listOf(rampObj(30, "Forest"), rampObj(31, "Forest")).toPersistentList(),
                        hand = listOf(rampObj(10, inHand)).toPersistentList(),
                        graveyard = persistentListOf(),
                        priorityStatus = PriorityStatus.HOLDS_PRIORITY,
                    ),
                bob to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(GameObject(ObjectId(40), CardRef("Mountain"), bob)),
                        hand = persistentListOf(),
                        graveyard = persistentListOf(),
                        priorityStatus = PriorityStatus.NONE,
                    ),
            ),
        turn = Turn(alice, RAMP_BOARD_TURN, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(battlefield.toPersistentList(), persistentListOf(), persistentListOf()),
        nextObjectId = 100,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )
}
