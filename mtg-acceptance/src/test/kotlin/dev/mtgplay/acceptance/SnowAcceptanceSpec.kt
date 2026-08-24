package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.event.GameEvent
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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The snow packet driven end-to-end through the real engine by [ScriptedGame] (which invariant-checks
 * every transition): the three Snow-Covered basics are *played* as lands and pay for spells, and Skred
 * is cast for real at a real creature, with its damage counting the snow permanents its controller
 * controls **as it resolves** (CR 608.2).
 *
 * Nothing here is asserted off a definition — every number comes from a game that was played.
 */
class SnowAcceptanceSpec :
    StringSpec({

        "CR 305.1 / CR 205.4a: each Snow-Covered basic is played as a land, never cast, and enters untapped" {
            SNOW_BASICS.forEach { name ->
                val game = snowGame(aliceHand = listOf(name))
                val window = game.action()
                window.options.filterIsInstance<PriorityOption.PlayLand>().map { it.card } shouldContain CardRef(name)
                window.options.filterIsInstance<PriorityOption.CastSpell>().map { it.card } shouldNotContain
                    CardRef(name)

                game.playLand(name)
                game.state.sharedZones.battlefield
                    .single()
                    .tapped
                    .shouldBeFalse()
            }
        }

        "CR 305.6: a Snow-Covered Mountain's authored {T}: Add {R} pays for a {R} spell the same turn" {
            val game =
                snowGame(
                    aliceHand = listOf("Skred"),
                    aliceBattlefield = listOf("Snow-Covered Mountain"),
                    bobBattlefield = listOf("Grizzly Bears"),
                )
            game.castSkredAt("Grizzly Bears")
            game.settle()
            // The land paid, so it is tapped — and it is still a snow permanent, which is why the
            // damage below is 1 and not 0: the clause counts permanents, not available mana.
            game.snowLand("Snow-Covered Mountain").tapped shouldBe true
            game.creature("Grizzly Bears").damageMarked shouldBe 1
        }

        "CR 608.2: Skred's damage is the snow permanents its controller controls, counted on resolution" {
            // One extra snow land at a time; the 2/2 survives 1 damage and dies to 2 (CR 704.5g).
            skredWith(listOf("Snow-Covered Mountain"))
                .creature("Grizzly Bears")
                .damageMarked shouldBe 1

            val two = skredWith(listOf("Snow-Covered Mountain", "Snow-Covered Island"))
            two.state.sharedZones.battlefield
                .map { it.card } shouldNotContain CardRef("Grizzly Bears")
            two.state.events
                .filterIsInstance<GameEvent.DamageDealt>()
                .map { it.amount } shouldContainExactly listOf(2)

            val three =
                skredWith(
                    listOf("Snow-Covered Mountain", "Snow-Covered Island", "Snow-Covered Plains"),
                )
            three.state.events
                .filterIsInstance<GameEvent.DamageDealt>()
                .map { it.amount } shouldContainExactly listOf(3)
        }

        "CR 120.8: with no snow permanent at all Skred resolves and deals no damage — a plain Mountain is not snow" {
            val game =
                snowGame(
                    aliceHand = listOf("Skred"),
                    aliceBattlefield = listOf("Mountain"),
                    bobBattlefield = listOf("Grizzly Bears"),
                )
            game.castSkredAt("Grizzly Bears")
            game.settle()
            // The spell resolved (it is in the graveyard, not on the stack) but dealt nothing.
            game.state.events
                .filterIsInstance<GameEvent.DamageDealt>()
                .shouldBeEmpty()
            game.creature("Grizzly Bears").damageMarked shouldBe 0
            game.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContain CardRef("Skred")
        }

        "CR 608.2: an opponent's snow permanents are not counted — the clause is 'you control'" {
            val game =
                snowGame(
                    aliceHand = listOf("Skred"),
                    aliceBattlefield = listOf("Snow-Covered Mountain"),
                    bobBattlefield =
                        listOf(
                            "Grizzly Bears",
                            "Snow-Covered Island",
                            "Snow-Covered Plains",
                            "Snow-Covered Mountain",
                        ),
                )
            game.castSkredAt("Grizzly Bears")
            game.settle()
            // Four snow permanents are on the battlefield; alice controls one, so Skred deals 1.
            game.state.sharedZones.battlefield
                .count { it.card.name.startsWith("Snow-Covered") } shouldBe 4
            game.creature("Grizzly Bears").damageMarked shouldBe 1
        }

        "CR 115.1a: Skred's enumerated targets are creatures only — no player is ever offered" {
            val game =
                snowGame(
                    aliceHand = listOf("Skred"),
                    aliceBattlefield = listOf("Snow-Covered Mountain", "Grizzly Bears"),
                    bobBattlefield = listOf("Standing Troops"),
                )
            game.castOption("Skred")
            val targets = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
            targets.options shouldNotContain Target.Player(alice)
            targets.options shouldNotContain Target.Player(bob)
            // Both seats' creatures are offered, and nothing else — Skred cannot go to the face.
            targets.options shouldHaveSize 2
            targets.options shouldContain Target.Permanent(game.creature("Grizzly Bears").id)
            targets.options shouldContain Target.Permanent(game.creature("Standing Troops").id)
        }

        "CR 601.2c: with no creature on the battlefield Skred is not an enumerated cast at all" {
            val game =
                snowGame(
                    aliceHand = listOf("Skred"),
                    aliceBattlefield = listOf("Snow-Covered Mountain"),
                )
            game
                .action()
                .options
                .filterIsInstance<PriorityOption.CastSpell>()
                .map { it.card } shouldNotContain CardRef("Skred")
        }

        "CR 608.2b: Skred fizzles when its only target dies before it resolves" {
            // alice Skreds bob's Grizzly Bears, then Bolts the same creature in response. LIFO makes
            // the Bolt resolve first and kill the 2/2 (CR 704.5g); Skred then finds its only target
            // gone and does not resolve — no damage, straight to the graveyard.
            val game =
                snowGame(
                    aliceHand = listOf("Skred", "Lightning Bolt"),
                    aliceBattlefield = listOf("Snow-Covered Mountain", "Mountain"),
                    bobBattlefield = listOf("Grizzly Bears"),
                )
            val bears = game.creature("Grizzly Bears").id
            game.castSkredAt("Grizzly Bears")
            game.castTargeting("Lightning Bolt", Target.Permanent(bears))
            game.settle()

            game.state.events
                .filterIsInstance<GameEvent.SpellFizzled>()
                .map { it.card } shouldContainExactly listOf(CardRef("Skred"))
            // Only the Bolt's 3 ever landed; Skred's 1 was never dealt.
            game.state.events
                .filterIsInstance<GameEvent.DamageDealt>()
                .map { it.amount } shouldContainExactly listOf(BOLT_DAMAGE)
        }
    })

/** The three Snow-Covered basics this packet encodes (CR 205.4a). */
private val SNOW_BASICS = listOf("Snow-Covered Island", "Snow-Covered Mountain", "Snow-Covered Plains")

/** What a resolving Lightning Bolt deals (CR 119.3) — the response that makes Skred's fizzle happen. */
private const val BOLT_DAMAGE: Int = 3

/** The turn these scenarios start on — alice's, late enough that nothing is summoning sick. */
private const val SNOW_TURN: Int = 3

/** Filler library cards, so an incidental draw step never decks a seat out (CR 704.5b). */
private const val LIBRARY_FILLER: Int = 6

/** Runaway guard for [settle]. */
private const val MAX_SNOW_DRIVE_STEPS: Int = 200

/**
 * A finished Skred at bob's Grizzly Bears, cast off [aliceLands] — every scenario in the
 * count-varies-with-the-board case differs only in that list.
 */
private fun skredWith(aliceLands: List<String>): ScriptedGame {
    val game =
        snowGame(
            aliceHand = listOf("Skred"),
            aliceBattlefield = aliceLands,
            bobBattlefield = listOf("Grizzly Bears"),
        )
    game.castSkredAt("Grizzly Bears")
    return game.settle()
}

/** The current priority window, which must be a [DecisionRequest.ChooseAction] (CR 117). */
private fun ScriptedGame.action(): DecisionRequest.ChooseAction =
    pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()

/** The single battlefield object of card [name], whichever seat owns it. */
private fun ScriptedGame.creature(name: String): GameObject =
    state.sharedZones.battlefield.single { it.card == CardRef(name) }

/** The single battlefield land of card [name] alice controls. */
private fun ScriptedGame.snowLand(name: String): GameObject =
    state.sharedZones.battlefield.single { it.card == CardRef(name) && it.owner == alice }

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

/** Casts Skred at the battlefield creature named [target] (CR 115.1a). */
private fun ScriptedGame.castSkredAt(target: String): ScriptedGame =
    castTargeting("Skred", Target.Permanent(creature(target).id))

/**
 * Advances until the stack is empty **and** no trigger is still waiting to be put on it (CR 603.3b) —
 * the "this spell and everything it set off has finished" pause.
 */
private fun ScriptedGame.settle(): ScriptedGame {
    var steps = 0

    fun done() = state.sharedZones.stack.isEmpty() && state.pendingTriggers.isEmpty()
    while (!done() && !isOver && steps < MAX_SNOW_DRIVE_STEPS) {
        when (val request = pendingRequest) {
            is DecisionRequest.ChooseAction -> pass()
            is DecisionRequest.OrderTriggers ->
                apply(Decision.MultiSelect(request.id, request.options.indices.toList()))
            is DecisionRequest.DeclareAttackers -> apply(Decision.MultiSelect(request.id, emptyList()))
            is DecisionRequest.DeclareBlockers -> apply(Decision.MultiSelect(request.id, emptyList()))
            else -> error("settle cannot answer $request")
        }
        steps++
    }
    check(done()) { "the stack did not settle within $MAX_SNOW_DRIVE_STEPS steps" }
    return this
}

/**
 * A [ScriptedGame] resumed from a handcrafted precombat-main state (ADR-004) over the real [MvpCards]
 * definitions: alice holds priority on turn [SNOW_TURN] with the given hand and battlefield, bob has
 * the given battlefield, and both seats keep a small filler library. Nothing on either battlefield is
 * summoning sick.
 */
private fun snowGame(
    aliceHand: List<String> = emptyList(),
    aliceBattlefield: List<String> = emptyList(),
    bobBattlefield: List<String> = emptyList(),
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
    val bobField = objects(bobBattlefield, bob)
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
            turn = Turn(alice, SNOW_TURN, TurnPhase.PRECOMBAT_MAIN, null),
            sharedZones =
                SharedZones(
                    battlefield = (aliceField + bobField).toPersistentList(),
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
