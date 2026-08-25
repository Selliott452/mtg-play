package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
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
import dev.mtgplay.rules.decision.PriorityOption
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * Gingerbread Cabin driven end-to-end through the real engine by [ScriptedGame], which
 * invariant-checks every transition.
 *
 * It is the first real card to reach the **play-land** enters-the-battlefield path, which is the
 * point of testing it here rather than off its definition. That path silently dropped every CR 603.6a
 * trigger until the fix the gauntlet triage records as **T18**, so "the land arrives untapped" is not
 * the interesting assertion — "the Food token actually exists afterwards" is, and on the pre-fix
 * engine it did not.
 *
 * Three properties, in the order the card reads:
 * - the CR 614.1c conditional clause resolves against the board *as the land enters*, counting the
 *   **other** Forests only;
 * - the CR 603.6a trigger fires exactly when the land in fact entered untapped;
 * - the Food token it creates is a real permanent whose own activated ability can be activated
 *   (CR 602), which is what makes the token more than a name on the battlefield.
 */
class GingerbreadCabinAcceptanceSpec :
    StringSpec({

        "CR 614.1c: played under three other Forests, Gingerbread Cabin enters tapped and makes no Food" {
            val game = cabinGame(forests = GINGERBREAD_CABIN_FORESTS - 1)
            game.playCabin()

            game.cabin().tapped.shouldBeTrue()
            // CR 603.6a: the "enters untapped" condition is false, so nothing triggered at all — the
            // stack is empty rather than holding an ability that would resolve doing nothing.
            game.state.sharedZones.stack
                .shouldBeEmpty()
            game.state.pendingTriggers.shouldBeEmpty()
            game.foodTokens() shouldBe 0
        }

        "CR 614.1c and CR 603.6a: played under three other Forests, it enters untapped and creates a Food token" {
            val game = cabinGame(forests = GINGERBREAD_CABIN_FORESTS)
            game.playCabin()

            game.cabin().tapped.shouldBeFalse()
            game.settleTriggers()
            game.foodTokens() shouldBe 1
        }

        "CR 614.1c: the Cabin does not count itself — three Forests where one is a second Cabin still enters untapped" {
            // Two of the three are ordinary Forests and the third is another Gingerbread Cabin, which
            // has the Forest land type; the entering copy is not on the battlefield yet, so "other" is
            // satisfied by exactly these three.
            val game = cabinGame(forests = GINGERBREAD_CABIN_FORESTS - 1, otherCabins = 1)
            game.playCabin()

            game.cabin().tapped.shouldBeFalse()
        }

        "CR 614.1c: a fourth Forest arriving later does not untap a Cabin that already entered tapped" {
            // The clause replaced an entering event; it is not a continuous effect that keeps looking.
            val game = cabinGame(forests = GINGERBREAD_CABIN_FORESTS - 1, extraForestInHand = true)
            game.playCabin()
            game.cabin().tapped.shouldBeTrue()
            game.settleTriggers()

            game.cabin().tapped.shouldBeTrue()
            game.foodTokens() shouldBe 0
        }

        "CR 602: the Food token's own activated ability is activatable, and gains its controller 3 life" {
            val game = cabinGame(forests = GINGERBREAD_CABIN_FORESTS, untappedMountains = 2)
            game.playCabin()
            game.settleTriggers()
            game.foodTokens() shouldBe 1

            val before =
                game.state.players
                    .getValue(alice)
                    .life
            game.activate("Food")
            val payment = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
            game.apply(Decision.SingleSelect(payment.id, 0))
            game.settleTriggers()

            game.state.players
                .getValue(alice)
                .life shouldBe before + FOOD_TOKEN_LIFE
            // CR 701.17: the sacrifice cost consumed the token, so it is gone rather than merely tapped.
            game.foodTokens() shouldBe 0
        }
    })

/** The Forests Gingerbread Cabin must see to enter untapped (CR 614.1c). */
private const val GINGERBREAD_CABIN_FORESTS: Int = 3

/** The life the Food token's ability gains (CR 120.1). */
private const val FOOD_TOKEN_LIFE: Int = 3

/** Runaway guard for the settle loop. */
private const val MAX_SETTLE_STEPS: Int = 40

private const val CABIN: String = "Gingerbread Cabin"

/** Plays the Cabin from alice's hand via the CR 116.2a special action. */
private fun ScriptedGame.playCabin(): ScriptedGame {
    val window = pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
    val index = window.options.indexOfFirst { it is PriorityOption.PlayLand && it.card == CardRef(CABIN) }
    check(index >= 0) { "no PlayLand option for $CABIN in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
}

/** Activates the first ability of the battlefield permanent named [name] (CR 602.2). */
private fun ScriptedGame.activate(name: String): ScriptedGame {
    val window = pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
    val index = window.options.indexOfFirst { it is PriorityOption.ActivateAbility && it.card == CardRef(name) }
    check(index >= 0) { "no ActivateAbility option for $name in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
}

/**
 * Passes priority until nothing is on the stack and nothing is pending, so a fired trigger has
 * resolved (CR 608.1). Stops at the first non-priority request rather than answering it blindly.
 */
private fun ScriptedGame.settleTriggers(): ScriptedGame {
    var steps = 0
    var settled = false
    while (steps < MAX_SETTLE_STEPS && !isOver && !settled) {
        val request = pendingRequest
        val nothingLeft = state.sharedZones.stack.isEmpty() && state.pendingTriggers.isEmpty() && steps > 0
        if (request !is DecisionRequest.ChooseAction || nothingLeft) {
            settled = true
        } else {
            val pass = request.options.indexOfFirst { it is PriorityOption.Pass }
            check(pass >= 0) { "CR 117.3d: passing must always be enumerated" }
            apply(Decision.SingleSelect(request.id, pass))
            steps += 1
        }
    }
    return this
}

/**
 * The battlefield object of the Cabin alice just played — the highest-id one, since a played land is
 * a brand-new object (CR 400.7) and ids are allocated in order. Named this way because one scenario
 * puts a *second* Cabin on the battlefield to be counted, and the entering copy is the one under test.
 */
private fun ScriptedGame.cabin(): GameObject =
    state.sharedZones.battlefield
        .filter { it.card == CardRef(CABIN) }
        .maxBy { it.id.value }

/** How many Food tokens are on the battlefield (CR 111.4). */
private fun ScriptedGame.foodTokens(): Int = state.sharedZones.battlefield.count { it.card == CardRef("Food") }

/**
 * A [ScriptedGame] resumed from a handcrafted precombat-main state (ADR-004): alice holds priority
 * with a Gingerbread Cabin in hand, [forests] basic Forests and [otherCabins] further Cabins already
 * on the battlefield, plus [untappedMountains] Mountains to fund the Food token's `{2}`. Real
 * [MvpCards] definitions throughout; every transition is invariant-checked by the driver.
 */
private fun cabinGame(
    forests: Int,
    otherCabins: Int = 0,
    untappedMountains: Int = 0,
    extraForestInHand: Boolean = false,
): ScriptedGame {
    var nextId = 0L

    fun obj(name: String): GameObject = GameObject(ObjectId(nextId++), CardRef(name), alice)

    val battlefield =
        List(forests) { obj("Forest") } +
            List(otherCabins) { obj(CABIN) } +
            List(untappedMountains) { obj("Mountain") }
    val hand = listOf(obj(CABIN)) + if (extraForestInHand) listOf(obj("Forest")) else emptyList()

    fun seat(
        seat: PlayerId,
        cards: List<GameObject>,
    ) = PlayerState(
        life = STARTING_LIFE,
        library = persistentListOf(),
        hand = cards.toPersistentList(),
        graveyard = persistentListOf(),
        priorityStatus = if (seat == alice) PriorityStatus.HOLDS_PRIORITY else PriorityStatus.NONE,
    )

    val state =
        GameState(
            players = persistentMapOf(alice to seat(alice, hand), bob to seat(bob, emptyList())),
            turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
            sharedZones =
                SharedZones(
                    battlefield = battlefield.toPersistentList(),
                    stack = persistentListOf(),
                    exile = persistentListOf(),
                ),
            nextObjectId = nextId,
            rng = Rng(0),
            events = persistentListOf(),
            definitions = MvpCards.definitions.toPersistentMap(),
        )
    return ScriptedGame.startFrom(state)
}
