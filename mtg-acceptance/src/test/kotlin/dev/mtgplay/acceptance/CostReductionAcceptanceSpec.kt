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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The `FW-COST` cards driven end-to-end through the real engine by [ScriptedGame], which
 * invariant-checks every transition (CR 601.2f, docs/design/cost-modification.md).
 *
 * Nothing here is asserted off a definition: each spell is genuinely cast off a real Grixis Affinity
 * board of artifact lands, its `ChoosePaymentPlan` is the one an agent would be handed, and the
 * payment actually executes. That last part is the point — a cost divergence between what enumeration
 * priced, what the request offered, and what the pipeline pays surfaces here as a loud failure rather
 * than as a plausible wrong game.
 *
 * The artifact lands do double duty on purpose: they are both the mana that pays the reduced cost and
 * the artifacts that reduce it, which is exactly how the deck works.
 */
class CostReductionAcceptanceSpec :
    StringSpec({

        "CR 702.41a: Myr Enforcer's {7} is cast for {3} off four artifact lands" {
            // Four artifact lands: affinity takes {7} down to {3}, and the same four lands pay it.
            // Without the reduction {7} is unpayable off four lands, so this cast exists only because
            // cost modification works (ADR-005 in both directions).
            val game = affinityGame(artifactLands = 4, inHand = "Myr Enforcer")
            game.castOption("Myr Enforcer")
            val payment = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
            payment.cost.render() shouldBe "{3}"
            payment.options.forEach { it.payments.size shouldBe 3 }

            game.payFirstPlan().settle()
            game.state.sharedZones.battlefield
                .map { it.card } shouldContain CardRef("Myr Enforcer")
            // Every symbol was paid: nothing floats (the MANA_POOL_EMPTY_AT_PAUSE invariant).
            game.state.players
                .getValue(alice)
                .manaPool
                .shouldBeEmpty()
        }

        "CR 601.2f: with seven artifacts Myr Enforcer costs {0} and its payment is the empty plan" {
            // The floor, on a real board. The request still surfaces with exactly one option — a {0}
            // cost is a real cost, not an absent one, and P2.1's no-auto-pass rule keeps replay logs
            // canonical even when there is nothing to decide.
            val game = affinityGame(artifactLands = 7, inHand = "Myr Enforcer")
            game.castOption("Myr Enforcer")
            val payment = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
            payment.cost.render() shouldBe "{0}"
            payment.options.size shouldBe 1
            payment.options
                .single()
                .payments
                .shouldBeEmpty()

            game.payFirstPlan().settle()
            game.state.sharedZones.battlefield
                .map { it.card } shouldContain CardRef("Myr Enforcer")
            // Not one land was tapped: the whole cost was reduced away.
            game.state.sharedZones.battlefield
                .none { it.tapped } shouldBe true
        }

        "CR 118.7a: Thoughtcast's {4}{U} floors at {U}, and its two cards are still drawn" {
            // Six artifact lands is two more reduction than the generic component has to give, and the
            // blue pip survives regardless — a generic reduction cannot touch a coloured symbol.
            val game = affinityGame(artifactLands = 6, inHand = "Thoughtcast")
            game.castOption("Thoughtcast")
            val payment = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
            payment.cost.render() shouldBe "{U}"

            val handBefore =
                game.state.players
                    .getValue(alice)
                    .hand
                    .size
            game.payFirstPlan().settle()
            // The Thoughtcast left the hand and drew two: net +1.
            game.state.players
                .getValue(alice)
                .hand
                .size shouldBe handBefore + 1
            game.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContain CardRef("Thoughtcast")
        }

        "ADR-005: an affinity spell is not enumerated when even its reduced cost is unpayable" {
            // One artifact land: Myr Enforcer is {6}, and one land cannot pay it. The option must be
            // absent rather than offered and then dead-ending mid-pipeline.
            val game = affinityGame(artifactLands = 1, inHand = "Myr Enforcer")
            val window = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            window.options
                .filterIsInstance<PriorityOption.CastSpell>()
                .map { it.card } shouldNotContain CardRef("Myr Enforcer")
        }

        "CR 702.41a: the spell being cast is not among the artifacts it counts" {
            // Myr Enforcer is itself an artifact creature. With three artifact lands it costs {4}, not
            // {3}: counting itself would be the obvious off-by-one and is the one this pins.
            val game = affinityGame(artifactLands = 3, inHand = "Myr Enforcer", extraLands = 2)
            game.castOption("Myr Enforcer")
            game.pendingRequest
                .shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
                .cost
                .render() shouldBe "{4}"
        }

        "CR 702.41a: a resolved affinity creature then reduces the next one" {
            // Two Myr Enforcers in hand over three artifact lands. The first costs {4}; once it has
            // resolved it is a fourth artifact on the battlefield, so the second costs {3}. The count
            // is read fresh at each CR 601.2f, never cached.
            val game =
                affinityGame(
                    artifactLands = 3,
                    inHand = "Myr Enforcer",
                    alsoInHand = listOf("Myr Enforcer"),
                    extraLands = 6,
                )
            game.castOption("Myr Enforcer")
            game.pendingRequest
                .shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
                .cost
                .render() shouldBe "{4}"
            game.payFirstPlan().settle()

            game.castOption("Myr Enforcer")
            game.pendingRequest
                .shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
                .cost
                .render() shouldBe "{3}"
        }

        "CR 404: Cryptic Serpent's {5}{U}{U} tracks its controller's graveyard and floors at {U}{U}" {
            val game =
                serpentGame(
                    graveyard = listOf("Lightning Bolt", "Ponder", "Preordain"),
                )
            game.castOption("Cryptic Serpent")
            game.pendingRequest
                .shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
                .cost
                .render() shouldBe "{2}{U}{U}"
        }

        "CR 118.7a: seven instants and sorceries leave Cryptic Serpent at {U}{U}, never less" {
            val game =
                serpentGame(
                    graveyard =
                        listOf(
                            "Lightning Bolt",
                            "Ponder",
                            "Preordain",
                            "Brainstorm",
                            "Mental Note",
                            "Thought Scour",
                            "Faithless Looting",
                        ),
                )
            game.castOption("Cryptic Serpent")
            val payment = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
            payment.cost.render() shouldBe "{U}{U}"
            payment.options.forEach { it.payments.size shouldBe 2 }
            game.payFirstPlan().settle()
            game.state.sharedZones.battlefield
                .map { it.card } shouldContain CardRef("Cryptic Serpent")
        }

        "CR 404: a graveyard of the wrong card types reduces Cryptic Serpent by nothing" {
            // Creature and land cards in the graveyard are not instants or sorceries. A predicate that
            // counted the zone's size instead of its card types would pass every other test in this
            // file and fail only here.
            val game = serpentGame(graveyard = listOf("Mountain", "Island", "Grizzly Bears"))
            game.castOption("Cryptic Serpent")
            game.pendingRequest
                .shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
                .cost
                .render() shouldBe "{5}{U}{U}"
        }
    })

/** The turn these scenarios resume on — late enough that nothing is summoning sick by construction. */
private const val COST_TURN: Int = 5

/** Runaway guard for [settle]. */
private const val MAX_COST_DRIVE_STEPS: Int = 200

/** Spare library cards per seat, so an incidental draw step never decks a scenario out (CR 704.5c). */
private const val COST_SPARE_LIBRARY: Int = 8

/** Lands enough that Cryptic Serpent's unreduced `{5}{U}{U}` is payable in every serpent scenario. */
private const val SERPENT_LANDS: Int = 7

/**
 * A board of [artifactLands] Seats of the Synod (artifact lands: both the artifacts affinity counts
 * and the mana that pays the reduced cost) plus [extraLands] Islands, with [inHand] and [alsoInHand]
 * in hand.
 */
private fun affinityGame(
    artifactLands: Int,
    inHand: String,
    alsoInHand: List<String> = emptyList(),
    extraLands: Int = 0,
): ScriptedGame {
    val battlefield =
        List(artifactLands) { "Seat of the Synod" } + List(extraLands) { "Island" }
    return costGame(hand = listOf(inHand) + alsoInHand, battlefield = battlefield)
}

/** A board with enough Islands to pay Cryptic Serpent unreduced, and [graveyard] in the graveyard. */
private fun serpentGame(graveyard: List<String>): ScriptedGame =
    costGame(
        hand = listOf("Cryptic Serpent"),
        battlefield = List(SERPENT_LANDS) { "Island" },
        graveyard = graveyard,
    )

/**
 * A [ScriptedGame] resumed from a handcrafted precombat-main state (ADR-004) over the real [MvpCards]
 * definitions: alice holds priority on turn [COST_TURN] with the given zones, every battlefield
 * permanent already settled in, and both seats padded so a turn walk never decks anyone out. Every
 * transition is invariant-checked.
 */
private fun costGame(
    hand: List<String>,
    battlefield: List<String>,
    graveyard: List<String> = emptyList(),
): ScriptedGame {
    var nextId = 0L

    fun objects(
        names: List<String>,
        owner: PlayerId,
        settled: Boolean = false,
    ): List<GameObject> =
        names.map { name ->
            GameObject(ObjectId(nextId++), CardRef(name), owner).let {
                if (settled) it.copy(summoningSick = false) else it
            }
        }

    val aliceField = objects(battlefield, alice, settled = true)
    val aliceHand = objects(hand, alice)
    val aliceGrave = objects(graveyard, alice)
    val aliceLibrary = objects(List(COST_SPARE_LIBRARY) { "Island" }, alice)
    val bobLibrary = objects(List(COST_SPARE_LIBRARY) { "Mountain" }, bob)

    val state =
        GameState(
            players =
                persistentMapOf(
                    alice to
                        PlayerState(
                            life = STARTING_LIFE,
                            library = aliceLibrary.toPersistentList(),
                            hand = aliceHand.toPersistentList(),
                            graveyard = aliceGrave.toPersistentList(),
                            priorityStatus = PriorityStatus.HOLDS_PRIORITY,
                        ),
                    bob to
                        PlayerState(
                            life = STARTING_LIFE,
                            library = bobLibrary.toPersistentList(),
                            hand = persistentListOf(),
                            graveyard = persistentListOf(),
                            priorityStatus = PriorityStatus.NONE,
                        ),
                ),
            turn = Turn(alice, COST_TURN, TurnPhase.PRECOMBAT_MAIN, null),
            sharedZones = SharedZones(aliceField.toPersistentList(), persistentListOf(), persistentListOf()),
            nextObjectId = nextId,
            rng = Rng(0),
            events = persistentListOf(),
            definitions = MvpCards.definitions.toPersistentMap(),
        )
    return ScriptedGame.startFrom(state)
}

/** Selects the cast option for [name] from the current priority window (CR 601.2). */
private fun ScriptedGame.castOption(name: String): ScriptedGame {
    val window = pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
    val index = window.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == CardRef(name) }
    check(index >= 0) { "no CastSpell option for $name in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
}

/** Answers the pending payment request with its first enumerated plan (CR 601.2g). */
private fun ScriptedGame.payFirstPlan(): ScriptedGame {
    val payment = pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
    return apply(Decision.SingleSelect(payment.id, 0))
}

/** Whether the stack is empty **and** no trigger is still waiting to be put on it (CR 603.3b). */
private val ScriptedGame.settled: Boolean
    get() = state.sharedZones.stack.isEmpty() && state.pendingTriggers.isEmpty()

/** Advances until the game is [settled], passing priority and declining combat along the way. */
private fun ScriptedGame.settle(): ScriptedGame {
    var steps = 0
    while (!settled && !isOver && steps < MAX_COST_DRIVE_STEPS) {
        when (val request = pendingRequest) {
            is DecisionRequest.ChooseAction -> {
                val index = request.options.indexOfFirst { it is PriorityOption.Pass }
                check(index >= 0) { "CR 117.3d: passing must always be enumerated" }
                apply(Decision.SingleSelect(request.id, index))
            }
            is DecisionRequest.OrderTriggers ->
                apply(Decision.MultiSelect(request.id, request.options.indices.toList()))
            is DecisionRequest.DeclareAttackers -> apply(Decision.MultiSelect(request.id, emptyList()))
            is DecisionRequest.DeclareBlockers -> apply(Decision.MultiSelect(request.id, emptyList()))
            else -> error("settle cannot answer $request")
        }
        steps++
    }
    return this
}
