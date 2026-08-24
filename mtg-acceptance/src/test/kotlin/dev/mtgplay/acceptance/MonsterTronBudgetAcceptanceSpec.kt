package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.random.Rng
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
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The size and shape of the payment action space a Monster Tron agent sees, pinned on one realistic
 * mid-game board — the `FW-MANA` counterpart of [BoglesRampBudgetAcceptanceSpec], and the
 * re-measurement docs/design/mana-payment.md §8.4 asked this packet for.
 *
 * The question it answers is the one the no-idle bound raises once an activation can yield three
 * mana. "Every activation spends at least one of its mana" is a weaker rule against an Urza's Tower
 * than against a Mountain: a plan may tap the Tower to pay a single `{1}` and waste two. That is
 * legal Magic and stays enumerable on purpose, but it widens the search, and the widening is what is
 * measured here rather than assumed away.
 *
 * The board is Monster Tron's own: **assembled** Tron — an Urza's Mine, an Urza's Tower and an
 * Urza's Power Plant, so the two Mines add `{C}{C}` each, the Power Plant `{C}{C}` and the Tower
 * `{C}{C}{C}` — plus a second Urza's Mine and a Forest. Eleven mana off five lands, in four source
 * classes (the two Mines collapse into one two-member class). Pool empty. The **broken** board is
 * the same five lands with the Power Plant swapped for a Forest, which fails all three conditions at
 * once while holding the land count fixed.
 */
class MonsterTronBudgetAcceptanceSpec :
    StringSpec({

        "docs/design/mana-payment.md §8.4: the enumerated payment options on a realistic Monster Tron board" {
            // Pinned counts. Like the Bogles pins these are not a correctness property on their own —
            // PaymentEnumerationSpec's oracle owns completeness — but an unexplained move is a signal.
            plansFor("Rancor") shouldHaveExactly TRON_OPTIONS_RANCOR
            plansFor("Malevolent Rumble") shouldHaveExactly TRON_OPTIONS_RUMBLE
            plansFor("Ancestral Mask") shouldHaveExactly TRON_OPTIONS_MASK
            plansFor("Scour from Existence") shouldHaveExactly TRON_OPTIONS_SCOUR
        }

        "docs/design/mana-payment.md §8.4: the same five lands with Tron broken, as the control column" {
            // Every Urza land back to one mana, which is also what the engine did for *every* source
            // before this framework. Read against the pins above: the framework adds three options to
            // `{2}{G}` and turns `{7}` from unpayable into seven — it does not inflate a cost the
            // Urza lands were already paying, and Rancor and Malevolent Rumble are untouched.
            plansFor("Rancor", assembled = false) shouldHaveExactly TRON_OPTIONS_RANCOR
            plansFor("Malevolent Rumble", assembled = false) shouldHaveExactly TRON_OPTIONS_RUMBLE
            plansFor("Ancestral Mask", assembled = false) shouldHaveExactly BROKEN_OPTIONS_MASK
            plansFor("Scour from Existence", assembled = false).shouldBeEmpty()
        }

        "CR 605.2: with Tron assembled, a {7} cost is payable off three lands" {
            val plans = plansFor("Scour from Existence")
            // 2 + 2 + 3 is the whole point of the deck: seven symbols paid by three activations.
            plans.minOf { it.activations.size } shouldBe TRON_FEWEST_LANDS_FOR_SEVEN
            plans.any { plan ->
                plan.activations.size == TRON_FEWEST_LANDS_FOR_SEVEN &&
                    plan.activations.sumOf { it.produced.size } == SEVEN
            } shouldBe true
        }

        "CR 605.2: breaking Tron makes the same five lands unable to pay the same cost" {
            // Every Urza land's condition now fails, so each adds exactly {C}: four mana, not eleven.
            // The difference is read from the battlefield when the mana ability resolves, and never
            // from anything locked in earlier — CR 605.2, not CR 601.2f.
            plansFor("Scour from Existence", assembled = false).shouldBeEmpty()
            plansFor("Scour from Existence").isNotEmpty() shouldBe true
        }

        "CR 605.2: an assembled Urza land is a different source class from an unassembled one" {
            // Assembled: the Tower's own profile says it adds three, and the agent is told so in the
            // option it is offered — the count is data on the plan, not a hidden runtime effect.
            urzaClasses(assembled = true).getValue(CardRef("Urza's Tower")) shouldBe
                listOf(List(THREE) { ManaType.COLORLESS })
            urzaClasses(assembled = true).getValue(CardRef("Urza's Mine")) shouldBe
                listOf(List(2) { ManaType.COLORLESS })
            // Broken: same printed cards, same battlefield size, every Urza land back to one mana.
            urzaClasses(assembled = false).values.toSet() shouldBe setOf(listOf(listOf(ManaType.COLORLESS)))
        }

        "docs/design/mana-payment.md §4: the no-idle bound still holds with three-mana activations" {
            TRON_PINNED_CARDS.forEach { card ->
                plansFor(card).forEach { plan ->
                    withClue("$card / $plan") {
                        // Each activation claims a distinct unit of demand, so the count can never
                        // exceed the mana the plan spends however much a single tap yields.
                        (plan.activations.size <= plan.payments.size) shouldBe true
                    }
                }
            }
            // And the bound bites where it is weakest: a one-symbol cost never taps two lands, even
            // though tapping a Tower for a single {G}-shaped symbol and wasting two would "spend one".
            plansFor("Rancor").maxOf { it.activations.size } shouldBe 1
        }

        "CR 605.2: the colorless cost the deck is built around taps far fewer lands than it pays mana" {
            val plans = plansFor("Scour from Existence")
            plans.minOf { it.activations.size } shouldBeLessThan plans.first().payments.size
            // The coloured costs get no such discount — the Urza lands add colorless, so a `{G}`
            // still needs the board's one Forest. Pinned so the framework is not credited with more
            // than it does: `{1}{G}` still takes two lands.
            plansFor("Malevolent Rumble").minOf { it.activations.size } shouldBe 2
        }
    })

/** The costs pinned above, in the order they are measured. */
private val TRON_PINNED_CARDS =
    listOf("Rancor", "Malevolent Rumble", "Ancestral Mask", "Scour from Existence")

/** Asserts a plan list's size, with the list rendered on failure so a move is diagnosable. */
private infix fun List<PaymentPlan>.shouldHaveExactly(expected: Int) {
    withClue(joinToString("\n")) { size shouldBe expected }
}

/**
 * Each Urza land's production profile as the enumerator hands it to an agent, gathered from the
 * options for Ancestral Mask's `{2}{G}` — a cost both boards can pay, so the two are comparable.
 */
private fun urzaClasses(assembled: Boolean): Map<CardRef, List<List<ManaType>>> =
    plansFor("Ancestral Mask", assembled = assembled)
        .flatMap { plan -> plan.activations.map { it.sourceClass } }
        .filter { it.card.name.startsWith("Urza's") }
        .associate { it.card to it.profile }

/**
 * The plans the engine enumerates for casting [card] from alice's hand on the pinned board — the
 * `ChoosePaymentPlan` options an agent would be handed (ADR-005), reached through a real cast.
 */
private fun plansFor(
    card: String,
    assembled: Boolean = true,
): List<PaymentPlan> {
    val game = ScriptedGame.startFrom(tronBoard(assembled, inHand = card))
    val window = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
    val castIndex = window.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == CardRef(card) }
    if (castIndex < 0) return emptyList()
    game.apply(Decision.SingleSelect(window.id, castIndex))
    if (game.pendingRequest is DecisionRequest.ChooseTargets) {
        val targets = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
        check(targets.options.isNotEmpty()) { "no legal target for $card" }
        game.apply(Decision.SingleSelect(targets.id, 0))
    }
    return game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>().options
}

/**
 * Rancor `{G}` — one symbol, and the Forest is the board's only green source, so the Urza lands
 * cannot widen it. Identical on both boards.
 */
private const val TRON_OPTIONS_RANCOR: Int = 1

/**
 * Malevolent Rumble `{1}{G}` — the generic off any of the three colorless classes, the green off the
 * Forest. Identical on both boards: three options, two lands, because a coloured symbol gets no
 * benefit from a bigger colorless activation.
 */
private const val TRON_OPTIONS_RUMBLE: Int = 3

/**
 * Ancestral Mask `{2}{G}` on assembled Tron — up from [BROKEN_OPTIONS_MASK], because each Urza land
 * can now cover **both** generics on its own, which is three new one-land-for-two-symbols lines.
 */
private const val TRON_OPTIONS_MASK: Int = 7

/**
 * Scour from Existence `{7}` — the cost assembled Tron exists to pay, and the framework's headline
 * number: **unpayable** with Tron broken, seven options with it assembled, the cheapest tapping
 * three lands for seven mana.
 */
private const val TRON_OPTIONS_SCOUR: Int = 7

/** Ancestral Mask `{2}{G}` with Tron broken: three one-mana colorless classes and two Forests. */
private const val BROKEN_OPTIONS_MASK: Int = 4

/** The turn the pinned board resumes on — late enough that nothing is summoning sick. */
private const val TRON_BOARD_TURN: Int = 6

/** The creature alice keeps around so the pinned Auras have something to enchant. */
private const val TRON_BOARD_CREATURE: Long = 20

private const val THREE: Int = 3
private const val SEVEN: Int = 7

/** With Tron assembled the Tower, a Mine and the Power Plant add 3 + 2 + 2 (CR 605.2). */
private const val TRON_FEWEST_LANDS_FOR_SEVEN: Int = 3

private fun tronObj(
    id: Long,
    name: String,
): GameObject = GameObject(ObjectId(id), CardRef(name), alice)

/**
 * The pinned mid-game Monster Tron board: five lands, one Gladecover Scout for the Auras, and
 * [inHand] as alice's only card in hand. With [assembled] the third land is the Urza's Power Plant
 * that completes Tron; without it, a Forest.
 */
private fun tronBoard(
    assembled: Boolean,
    inHand: String,
): GameState {
    val third = if (assembled) "Urza's Power Plant" else "Forest"
    val battlefield =
        listOf(
            tronObj(0, "Urza's Mine"),
            tronObj(1, "Urza's Tower"),
            tronObj(2, third),
            tronObj(3, "Urza's Mine"),
            tronObj(4, "Forest"),
            tronObj(TRON_BOARD_CREATURE, "Gladecover Scout").copy(summoningSick = false),
        )
    return GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = listOf(tronObj(30, "Forest"), tronObj(31, "Forest")).toPersistentList(),
                        hand = listOf(tronObj(10, inHand)).toPersistentList(),
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
        turn = Turn(alice, TRON_BOARD_TURN, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(battlefield.toPersistentList(), persistentListOf(), persistentListOf()),
        nextObjectId = 100,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = MvpCards.definitions.toPersistentMap(),
    )
}
