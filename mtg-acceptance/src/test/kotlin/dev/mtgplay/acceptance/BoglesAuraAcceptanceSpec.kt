package dev.mtgplay.acceptance

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
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.DefaultGameEngine
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.pendingRequestOf
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The P4.2 Bogles Auras exercised through the **real engine pipeline** (CR 601), not just the layer
 * computation: enchant-restriction legality is what the ADR-005 target enumeration surfaces at the
 * CR 601.2c target-choice window (CR 303.4a), and the Abundant-Growth mana grant is what real payment
 * enumeration reads to pay an off-color cost (docs/design/layer-system.md §6, §8). Every state here is
 * a valid engine input by construction (ADR-004): alice holds priority mid-window, so the engine
 * re-derives the pending request from the state alone.
 */
class BoglesAuraAcceptanceSpec :
    StringSpec({

        "CR 303.4a: Rancor (enchant creature) enumerates every creature as a legal enchant target, no land" {
            // A Forest (alice's mana), alice's Grizzly Bears (id 1), bob's Grizzly Bears (id 2).
            val game =
                castWindow(
                    hand = listOf("Rancor"),
                    battlefield =
                        listOf(
                            land(0, "Forest", alice),
                            creature(1, "Grizzly Bears", alice),
                            creature(2, "Grizzly Bears", bob),
                        ),
                )
            val targets = game.castThenTargets("Rancor")
            // Both creatures are legal (CR 303.4a: "enchant creature", no control clause); the land is not.
            targets shouldContainExactly listOf(Target.Permanent(ObjectId(1)), Target.Permanent(ObjectId(2)))
            targets shouldNotContain Target.Permanent(ObjectId(0))
        }

        "CR 303.4a: Cartouche of Solidarity (enchant creature you control) cannot target an opponent's creature" {
            // Plains (alice's mana), alice's Grizzly Bears (id 1), bob's Grizzly Bears (id 2).
            val game =
                castWindow(
                    hand = listOf("Cartouche of Solidarity"),
                    battlefield =
                        listOf(
                            land(0, "Plains", alice),
                            creature(1, "Grizzly Bears", alice),
                            creature(2, "Grizzly Bears", bob),
                        ),
                )
            val targets = game.castThenTargets("Cartouche of Solidarity")
            // "Creature you control" is ownership in the MVP pool (§4): only alice's creature is legal.
            targets shouldContainExactly listOf(Target.Permanent(ObjectId(1)))
            targets shouldNotContain Target.Permanent(ObjectId(2))
        }

        "CR 303.4a: Abundant Growth (enchant land) cannot target a creature — only lands are legal" {
            // Two lands (Forest = mana, Plains) and a creature; Abundant Growth may enchant either land.
            val game =
                castWindow(
                    hand = listOf("Abundant Growth"),
                    battlefield =
                        listOf(
                            land(0, "Forest", alice),
                            land(1, "Plains", alice),
                            creature(2, "Grizzly Bears", alice),
                        ),
                )
            val targets = game.castThenTargets("Abundant Growth")
            targets shouldContainExactly listOf(Target.Permanent(ObjectId(0)), Target.Permanent(ObjectId(1)))
            // The creature is not a legal enchant-land target (CR 303.4a).
            targets shouldNotContain Target.Permanent(ObjectId(2))
        }

        "CR 605.1a and §8: an Abundant-Growth-enchanted Forest taps for {R} through real payment enumeration" {
            // A Forest enchanted by Abundant Growth is alice's only mana; Lightning Bolt costs {R}.
            val game =
                castWindow(
                    hand = listOf("Lightning Bolt"),
                    battlefield =
                        listOf(
                            land(0, "Forest", alice),
                            GameObject(ObjectId(1), CardRef("Abundant Growth"), alice, attachedTo = ObjectId(0)),
                        ),
                )
            // Cast Lightning Bolt at bob; the only payment plan taps the Forest for {R} — off-color,
            // possible only because of the layer-6 any-color grant (CR 613 layer 6).
            val cast = game.request<DecisionRequest.ChooseAction>().castOption("Lightning Bolt")
            game.apply(cast)
            val target = game.request<DecisionRequest.ChooseTargets>()
            target.options shouldContain Target.Player(bob)
            game.apply(Decision.SingleSelect(target.id, target.options.indexOf(Target.Player(bob))))
            val payment = game.request<DecisionRequest.ChoosePaymentPlan>()
            game.apply(Decision.SingleSelect(payment.id, 0))

            // The enchanted Forest is now tapped (its {T} cost paid) — it produced the red mana.
            game.state.sharedZones.battlefield
                .first { it.card == CardRef("Forest") }
                .tapped shouldBe true

            // Alice passes, bob passes; the Bolt resolves and bob loses 3 life (CR 120.3a).
            game.pass()
            game.pass()
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - 3
        }

        "without the Abundant Growth grant a plain Forest makes only {G}, so Lightning Bolt ({R}) is uncastable" {
            // The before/after control for the off-color scenario: no aura, so {R} is unpayable and the
            // engine never even enumerates the Lightning Bolt cast (ADR-005: no dead-ending options).
            val game = castWindow(hand = listOf("Lightning Bolt"), battlefield = listOf(land(0, "Forest", alice)))
            val casts =
                game
                    .request<DecisionRequest.ChooseAction>()
                    .options
                    .filterIsInstance<PriorityOption.CastSpell>()
            casts shouldBe emptyList()
        }
    })

/** A minimal single-state engine driver over a handcrafted paused window (ADR-004). */
private class HandGame(
    start: GameState,
) {
    private val engine = DefaultGameEngine()

    // @PublishedApi so the inline reified [request] can read it at its call site.
    @PublishedApi
    internal var current: AdvanceResult =
        AdvanceResult.NeedsDecision(start, pendingRequestOf(start) ?: error("start state is not a pause point"))

    /** The current game state, paused or over. */
    val state: GameState
        get() =
            when (val result = current) {
                is AdvanceResult.NeedsDecision -> result.state
                is AdvanceResult.GameOver -> result.state
            }

    /** The pending request, checked to be of kind [R]. */
    inline fun <reified R : DecisionRequest> request(): R =
        (current as? AdvanceResult.NeedsDecision ?: error("game is over"))
            .request
            .shouldBeInstanceOf<R>()

    /** Applies [decision] to the pending window and advances. */
    fun apply(decision: Decision) {
        current = engine.advance(state, decision)
    }

    /** Passes the pending priority window (CR 117.3d). */
    fun pass() {
        val window = request<DecisionRequest.ChooseAction>()
        val passIndex = window.options.indexOfFirst { it is PriorityOption.Pass }
        check(passIndex >= 0) { "CR 117.3d: passing must always be enumerated, options were ${window.options}" }
        apply(Decision.SingleSelect(window.id, passIndex))
    }
}

/** The [PriorityOption.CastSpell] decision for [card] in this window (CR 601.2a). */
private fun DecisionRequest.ChooseAction.castOption(card: String): Decision.SingleSelect {
    val index = options.indexOfFirst { it is PriorityOption.CastSpell && it.card == CardRef(card) }
    check(index >= 0) { "no CastSpell option for $card in $options" }
    return Decision.SingleSelect(id, index)
}

/** Casts [card] from the pending priority window and returns the CR 601.2c legal enchant targets. */
private fun HandGame.castThenTargets(card: String): List<Target> {
    apply(request<DecisionRequest.ChooseAction>().castOption(card))
    return request<DecisionRequest.ChooseTargets>().options
}

/** A handcrafted priority window: alice holds priority in her precombat main over [battlefield]. */
private fun castWindow(
    hand: List<String>,
    battlefield: List<GameObject>,
): HandGame {
    var nextId = (battlefield.maxOfOrNull { it.id.value } ?: -1L) + 1
    val handObjects = hand.map { name -> GameObject(ObjectId(nextId), CardRef(name), alice).also { nextId += 1 } }

    fun seat(
        seat: PlayerId,
        handCards: List<GameObject>,
    ) = PlayerState(
        life = STARTING_LIFE,
        library = persistentListOf(),
        hand = handCards.toPersistentList(),
        graveyard = persistentListOf(),
        priorityStatus = if (seat == alice) PriorityStatus.HOLDS_PRIORITY else PriorityStatus.NONE,
    )

    val state =
        GameState(
            players = persistentMapOf(alice to seat(alice, handObjects), bob to seat(bob, emptyList())),
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
    return HandGame(state)
}

/** An untapped battlefield land [name] owned by [owner]. */
private fun land(
    id: Long,
    name: String,
    owner: PlayerId,
): GameObject = GameObject(ObjectId(id), CardRef(name), owner)

/** A battlefield creature [name] owned by [owner]. */
private fun creature(
    id: Long,
    name: String,
    owner: PlayerId,
): GameObject = GameObject(ObjectId(id), CardRef(name), owner)
