package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.decision.PriorityOption
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The `FW-MANACOST` cards reached through a **real cast**, on one realistic board — the end-to-end
 * counterpart of `ManaAbilityCostSpec`'s unit-level properties, and the third leg of the correctness
 * argument docs/design/mana-payment.md §8.3 sets out for anything the payment oracle cannot see.
 *
 * The board is Spy Combo's own green shell: a Wall of Roots, a Saruli Caretaker, a Barrels of Blasting
 * Jelly and two Forests, all settled. Between them they present all three new cost shapes at once, and
 * a cost that needs more than one of them is where a capacity model that counts only class membership
 * would go wrong.
 *
 * The two pinned budget boards (`BoglesRampBudgetAcceptanceSpec`, `MonsterTronBudgetAcceptanceSpec`)
 * are deliberately **not** touched by this packet, and their counts pass unmodified: no card on either
 * board has a costed mana ability, so every alternative there still has the same `[TapSelf]` cost it
 * had before and every legality clause reduces to its pre-packet form.
 */
class CostedManaSourceAcceptanceSpec :
    StringSpec({

        "docs/design/mana-payment.md §11: the enumerated payment options on a costed-source board" {
            // Pinned like the other budget boards: not a correctness property on its own — the oracle
            // in `mtg-rules` owns completeness — but an unexplained move is a signal worth a failure.
            plansFor("Rancor") shouldHaveExactly OPTIONS_RANCOR
            plansFor("Malevolent Rumble") shouldHaveExactly OPTIONS_RUMBLE
            plansFor("Ancestral Mask") shouldHaveExactly OPTIONS_MASK
        }

        "CR 602.1: every enumerated plan on the costed board executes exactly as declared" {
            // The completeness half is the oracle's; this is the *executability* half, reached through
            // the real CR 601 pipeline rather than through `payManaPlan` directly. A plan that
            // enumerates and cannot execute is the ADR-005 defect the whole model exists to prevent.
            listOf("Rancor", "Malevolent Rumble", "Ancestral Mask").forEach { card ->
                val options = plansFor(card)
                options.indices.forEach { index ->
                    withClue("$card / plan $index of ${options.size}") {
                        castWithPlan(card, index)
                    }
                }
            }
        }

        "CR 602.5b and CR 500.1: a once-each-turn source resets as the next turn begins" {
            // Wall of Roots taps for {G} once, is then no mana source at all for the rest of the turn,
            // and is one again on the opponent's turn — "each turn" is each player's turn, not each
            // round. Driven through the engine's own turn machinery, because the reset lives in
            // `beginTurn` beside the CR 302.6 summoning-sickness clearing.
            val game = ScriptedGame.startFrom(costedBoard(inHand = "Rancor"))
            val request = paymentRequest(game, "Rancor") ?: error("no payment request for Rancor")
            val wallPlan =
                request.options.indexOfFirst { plan ->
                    plan.activations.map { it.sourceClass.card } == listOf(CardRef("Wall of Roots"))
                }
            game.apply(Decision.SingleSelect(request.id, wallPlan))
            val wall = wallOfRootsOn(game.state)
            wall.counterCount(Counter.MINUS_ZERO_MINUS_ONE) shouldBe 1
            wall.manaAbilitiesActivatedThisTurn shouldBe setOf(0)

            // Pass into bob's turn; the record clears for every object as that turn begins.
            game.passUntil { it.turn.activePlayer == bob }
            wallOfRootsOn(game.state).manaAbilitiesActivatedThisTurn.shouldBeEmpty()
        }
    })

/** The board's Wall of Roots. */
private fun wallOfRootsOn(state: GameState): GameObject =
    state.sharedZones.battlefield.single { it.card == CardRef("Wall of Roots") }

/** Asserts a plan list's size, with the list rendered on failure so a move is diagnosable. */
private infix fun List<PaymentPlan>.shouldHaveExactly(expected: Int) {
    withClue(joinToString("\n")) { size shouldBe expected }
}

/**
 * Rancor `{G}` on the costed board. **Three** of these are the direct lines — the Forest class, the
 * Wall of Roots, the Saruli Caretaker choosing green — and the other **seven** route through the
 * Barrels of Blasting Jelly: one funder mana converted into the green that pays the cost, once per
 * (Forest, Wall, and each of the Caretaker's five colours).
 *
 * Every one of those seven is a real, distinct line — it spends the Barrels' once-each-turn
 * activation, which is a resource the rest of the turn no longer has — so none of them is idle by
 * the §4 rule. The measurement is recorded rather than argued away because it is the packet's honest
 * cost: **a mana filter multiplies the plan space by roughly (colours it can add × mana that can fund
 * it)**, and the weak no-idle bound cannot prune it, because a filter's output always has a sink.
 */
private const val OPTIONS_RANCOR: Int = 10

/** Malevolent Rumble `{1}{G}` — two symbols over the same sources, with the filter routing either. */
private const val OPTIONS_RUMBLE: Int = 80

/**
 * Ancestral Mask `{2}{G}` — the widest cost measured on any board so far, against the Bogles board's
 * 32 and the assembled Tron board's 7 for comparable costs. The multiplier is the two any-colour
 * sources (the Caretaker and the Barrels) rather than the number of lands: neither Tron nor Bogles
 * has one.
 */
private const val OPTIONS_MASK: Int = 106

/** The turn the pinned board resumes on — late enough that nothing is summoning sick. */
private const val COSTED_BOARD_TURN: Int = 6

private fun costedObj(
    id: Long,
    name: String,
): GameObject = GameObject(ObjectId(id), CardRef(name), alice).copy(summoningSick = false)

/**
 * The plans the engine enumerates for casting [card] from alice's hand on the pinned board — the
 * `ChoosePaymentPlan` options an agent would be handed (ADR-005), reached through a real cast.
 */
private fun plansFor(card: String): List<PaymentPlan> {
    val game = ScriptedGame.startFrom(costedBoard(inHand = card))
    return paymentRequest(game, card)?.options.orEmpty()
}

/**
 * Casts [card] choosing plan [planIndex] on a fresh copy of the board; the produced state is
 * invariant-checked by [ScriptedGame] on every step, so a plan that cannot execute fails here.
 */
private fun castWithPlan(
    card: String,
    planIndex: Int,
) {
    val game = ScriptedGame.startFrom(costedBoard(inHand = card))
    val request = paymentRequest(game, card) ?: error("no payment request for $card")
    game.apply(Decision.SingleSelect(request.id, planIndex))
}

/** Drives [game] to the `ChoosePaymentPlan` for casting [card], or `null` if the cast is unavailable. */
private fun paymentRequest(
    game: ScriptedGame,
    card: String,
): DecisionRequest.ChoosePaymentPlan? {
    val window = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
    val castIndex = window.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == CardRef(card) }
    if (castIndex < 0) return null
    game.apply(Decision.SingleSelect(window.id, castIndex))
    if (game.pendingRequest is DecisionRequest.ChooseTargets) {
        val targets = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
        check(targets.options.isNotEmpty()) { "no legal target for $card" }
        game.apply(Decision.SingleSelect(targets.id, 0))
    }
    return game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
}

/**
 * The pinned costed-source board: two Forests and the packet's three cards, plus a Gladecover Scout
 * for the Auras to enchant, with [inHand] as alice's only card in hand.
 */
private fun costedBoard(inHand: String): GameState {
    val battlefield =
        listOf(
            costedObj(0, "Forest"),
            costedObj(1, "Forest"),
            costedObj(2, "Wall of Roots"),
            costedObj(3, "Saruli Caretaker"),
            costedObj(4, "Barrels of Blasting Jelly"),
            costedObj(5, "Gladecover Scout"),
        )
    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = listOf(costedObj(30, "Forest"), costedObj(31, "Forest")).toPersistentList(),
                        hand = listOf(costedObj(10, inHand)).toPersistentList(),
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
        turn = Turn(alice, COSTED_BOARD_TURN, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(battlefield.toPersistentList(), persistentListOf(), persistentListOf()),
        nextObjectId = 100,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )
}
