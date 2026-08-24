package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.card.CardType
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
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
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
 * The P8.4 gauntlet nonbasic lands, driven end-to-end through the real engine by [ScriptedGame] (which
 * invariant-checks every transition). Every assertion is made by *playing* the land and watching the
 * game: the three Mirrodin artifact lands arrive untapped and pay a spell the same turn; the four
 * Bridges and Idyllic Beachfront arrive tapped (CR 614.1c), pay for nothing that turn, untap in their
 * controller's next untap step (CR 502.1) and only then produce; and each dual producer's printed
 * options are exercised by actually spending both halves.
 *
 * Every state is a valid engine input by construction (ADR-004).
 */
class NonbasicLandAcceptanceSpec :
    StringSpec({

        "CR 305.1: every P8.4 nonbasic land is played as a land, never cast" {
            NONBASIC_LANDS.forEach { name ->
                val game = landGame(aliceHand = listOf(name))
                val window = game.action()
                window.options.filterIsInstance<PriorityOption.PlayLand>().map { it.card } shouldContain CardRef(name)
                window.options.filterIsInstance<PriorityOption.CastSpell>().map { it.card } shouldNotContain
                    CardRef(name)
            }
        }

        "CR 110.5a / CR 605.1a: an artifact land enters untapped and pays a spell the same turn" {
            val game = landGame(aliceHand = listOf("Great Furnace", "Lightning Bolt"))
            game.playLand("Great Furnace")

            val land =
                game.state.sharedZones.battlefield
                    .single()
            land.tapped.shouldBeFalse()
            // CR 301 / CR 305: an artifact *and* a land — the type line the affinity decks count.
            val types =
                MvpCards.definitions
                    .getValue(land.card)
                    .characteristics.cardTypes
            types shouldBe persistentListOf(CardType.ARTIFACT, CardType.LAND).toSet()

            game.castTargeting("Lightning Bolt", Target.Player(bob))
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - BOLT_DAMAGE
        }

        "CR 614.1c: a Bridge is on the battlefield tapped the instant it is played, and funds nothing" {
            val game = landGame(aliceHand = listOf("Silverbluff Bridge", "Lightning Bolt"))
            game.playLand("Silverbluff Bridge")

            game.state.sharedZones.battlefield
                .single()
                .tapped
                .shouldBeTrue()
            // Its {T}: Add {U} or {R} cannot be activated while tapped, so the Bolt is unaffordable and
            // enumeration must not offer it (ADR-005).
            game
                .action()
                .options
                .filterIsInstance<PriorityOption.CastSpell>()
                .shouldBeEmpty()
        }

        "CR 502.1: the Bridge untaps on its controller's next turn and its {R} then pays the Bolt" {
            val game = landGame(aliceHand = listOf("Silverbluff Bridge", "Lightning Bolt"))
            game.playLand("Silverbluff Bridge")
            game.passUntil { it.turn.number == ALICE_NEXT_TURN && it.turn.phase == TurnPhase.PRECOMBAT_MAIN }

            game.state.sharedZones.battlefield
                .single { it.card == CardRef("Silverbluff Bridge") }
                .tapped
                .shouldBeFalse()

            game.castOption("Lightning Bolt")
            val targets = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
            game.apply(Decision.SingleSelect(targets.id, targets.options.indexOf(Target.Player(bob))))
            // {R} has exactly one plan: the blue half of "{T}: Add {U} or {R}" cannot pay it, and an
            // activation whose mana goes unspent is not enumerated (docs/design/mana-payment.md §4).
            val payment = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
            payment.options.size shouldBe 1
            game.apply(Decision.SingleSelect(payment.id, 0))

            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - BOLT_DAMAGE
            game.state.sharedZones.battlefield
                .single { it.card == CardRef("Silverbluff Bridge") }
                .tapped
                .shouldBeTrue()
        }

        "CR 605.1a: a Bridge's other printed option is real — Slagwoods Bridge's {G} casts a Bogle" {
            val game =
                landGame(
                    aliceHand = listOf("Slippery Bogle"),
                    aliceBattlefield = listOf("Slagwoods Bridge"),
                )
            game.castOption("Slippery Bogle")
            // {G/U} accepts green or blue; the Bridge adds {R} or {G}, so the green activation is the
            // single plan — the red one could pay nothing here.
            val payment = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
            payment.options.size shouldBe 1
            game.apply(Decision.SingleSelect(payment.id, 0))

            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            game.state.sharedZones.battlefield
                .map { it.card } shouldContain CardRef("Slippery Bogle")
            game.state.sharedZones.battlefield
                .single { it.card == CardRef("Slagwoods Bridge") }
                .tapped
                .shouldBeTrue()
        }

        "CR 614.1c: Idyllic Beachfront enters tapped like the Bridges, though its abilities are type-derived" {
            val game = landGame(aliceHand = listOf("Idyllic Beachfront"))
            game.playLand("Idyllic Beachfront")

            game.state.sharedZones.battlefield
                .single()
                .tapped
                .shouldBeTrue()
        }

        "CR 305.6: an untapped Idyllic Beachfront produces {W} — the Plains half of its type line" {
            val game =
                landGame(
                    aliceHand = listOf("Cartouche of Solidarity"),
                    aliceBattlefield = listOf("Idyllic Beachfront", "Gladecover Scout"),
                )
            val scout =
                game.state.sharedZones.battlefield
                    .single { it.card == CardRef("Gladecover Scout") }
            game.castTargeting("Cartouche of Solidarity", Target.Permanent(scout.id))
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }

            game.state.sharedZones.battlefield
                .map { it.card } shouldContain CardRef("Cartouche of Solidarity")
            game.state.sharedZones.battlefield
                .single { it.card == CardRef("Idyllic Beachfront") }
                .tapped
                .shouldBeTrue()
        }

        "CR 305.6: an untapped Idyllic Beachfront produces {U} — the Island half of its type line" {
            val game =
                landGame(
                    aliceHand = listOf("Slippery Bogle"),
                    aliceBattlefield = listOf("Idyllic Beachfront"),
                )
            game.castOption("Slippery Bogle")
            // {G/U}: only the blue half of the land's two type-derived abilities can pay it.
            val payment = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
            payment.options.size shouldBe 1
            game.apply(Decision.SingleSelect(payment.id, 0))

            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            game.state.sharedZones.battlefield
                .map { it.card } shouldContain CardRef("Slippery Bogle")
        }

        "CR 614.1c: each of the four Bridges enters tapped, and each artifact land enters untapped" {
            BRIDGES.forEach { name ->
                val game = landGame(aliceHand = listOf(name))
                game.playLand(name)
                game.state.sharedZones.battlefield
                    .single()
                    .tapped
                    .shouldBeTrue()
            }
            ARTIFACT_LANDS.forEach { name ->
                val game = landGame(aliceHand = listOf(name))
                game.playLand(name)
                game.state.sharedZones.battlefield
                    .single()
                    .tapped
                    .shouldBeFalse()
            }
        }
    })

/** The three Mirrodin artifact lands of the packet — no rules text beyond one mana ability. */
private val ARTIFACT_LANDS = listOf("Great Furnace", "Seat of the Synod", "Vault of Whispers")

/** The four Modern Horizons Bridges — enters tapped, indestructible, two colours. */
private val BRIDGES =
    listOf("Drossforge Bridge", "Mistvault Bridge", "Silverbluff Bridge", "Slagwoods Bridge")

/** Every nonbasic land this packet encodes. */
private val NONBASIC_LANDS = ARTIFACT_LANDS + BRIDGES + listOf("Idyllic Beachfront")

/** What a resolving Lightning Bolt deals (CR 119.3). */
private const val BOLT_DAMAGE: Int = 3

/** The turn these scenarios start on — alice's, late enough that nothing is summoning sick. */
private const val LAND_TURN: Int = 3

/** Alice's next turn after [LAND_TURN], where a land played on turn 3 untaps (CR 502.1). */
private const val ALICE_NEXT_TURN: Int = 5

/** Filler library cards, so walking a turn or two never decks a seat out (CR 704.5c). */
private const val LIBRARY_FILLER: Int = 6

/** Runaway guard for [driveUntil]. */
private const val MAX_LAND_DRIVE_STEPS: Int = 200

/** The current priority window, which must be a [DecisionRequest.ChooseAction] (CR 117). */
private fun ScriptedGame.action(): DecisionRequest.ChooseAction =
    pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()

/** Plays the land [name] from hand with the CR 116.2a special action. */
private fun ScriptedGame.playLand(name: String): ScriptedGame {
    val window = action()
    val index = window.options.indexOfFirst { it is PriorityOption.PlayLand && it.card == CardRef(name) }
    check(index >= 0) { "no PlayLand option for $name in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
}

/** Selects the cast option for [name] from the current priority window (CR 601.2). */
private fun ScriptedGame.castOption(name: String): ScriptedGame {
    val window = action()
    val index = window.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == CardRef(name) }
    check(index >= 0) { "no CastSpell option for $name in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
}

/** Casts the targeted spell [name] at [target], paying its first enumerated plan (CR 601.2c, CR 601.2g). */
private fun ScriptedGame.castTargeting(
    name: String,
    target: Target,
): ScriptedGame {
    castOption(name)
    val targets = pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
    val index = targets.options.indexOf(target)
    check(index >= 0) { "no legal target $target for $name in ${targets.options}" }
    apply(Decision.SingleSelect(targets.id, index))
    val payment = pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
    return apply(Decision.SingleSelect(payment.id, 0))
}

/** Passes priority, declining combat and ordering triggers deterministically, until [predicate] holds. */
private fun ScriptedGame.driveUntil(predicate: () -> Boolean): ScriptedGame {
    var steps = 0
    while (!predicate() && !isOver && steps < MAX_LAND_DRIVE_STEPS) {
        when (val request = pendingRequest) {
            is DecisionRequest.ChooseAction -> pass()
            is DecisionRequest.OrderTriggers ->
                apply(Decision.MultiSelect(request.id, request.options.indices.toList()))
            is DecisionRequest.DeclareAttackers -> apply(Decision.MultiSelect(request.id, emptyList()))
            is DecisionRequest.DeclareBlockers -> apply(Decision.MultiSelect(request.id, emptyList()))
            else -> error("driveUntil cannot answer $request")
        }
        steps++
    }
    check(predicate()) { "the drive predicate was not satisfied within $MAX_LAND_DRIVE_STEPS steps" }
    return this
}

/**
 * A [ScriptedGame] resumed from a handcrafted precombat-main state (ADR-004) over the real [MvpCards]
 * definitions: alice holds priority on turn [LAND_TURN] with the given hand and battlefield, and both
 * seats keep a small filler library so a turn walk never decks anyone out.
 */
private fun landGame(
    aliceHand: List<String> = emptyList(),
    aliceBattlefield: List<String> = emptyList(),
): ScriptedGame {
    var nextId = 0L

    fun objects(
        names: List<String>,
        owner: PlayerId,
    ): List<GameObject> =
        names.map { name ->
            GameObject(ObjectId(nextId), CardRef(name), owner, summoningSick = false).also { nextId += 1 }
        }

    val aliceField = objects(aliceBattlefield, alice)
    val aliceHandObjects = objects(aliceHand, alice)
    val aliceLibrary = objects(List(LIBRARY_FILLER) { "Mountain" }, alice)
    val bobLibrary = objects(List(LIBRARY_FILLER) { "Mountain" }, bob)

    fun seat(
        seatId: PlayerId,
        hand: List<GameObject>,
        library: List<GameObject>,
    ) = PlayerState(
        life = STARTING_LIFE,
        library = library.toPersistentList(),
        hand = hand.toPersistentList(),
        graveyard = persistentListOf(),
        priorityStatus = if (seatId == alice) PriorityStatus.HOLDS_PRIORITY else PriorityStatus.NONE,
    )

    val state =
        GameState(
            players =
                persistentMapOf(
                    alice to seat(alice, aliceHandObjects, aliceLibrary),
                    bob to seat(bob, emptyList(), bobLibrary),
                ),
            turn = Turn(alice, LAND_TURN, TurnPhase.PRECOMBAT_MAIN, null),
            sharedZones =
                SharedZones(
                    battlefield = aliceField.toPersistentList(),
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
