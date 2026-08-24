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
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The card-sweep packet's four cards — the two snow dual lands and the two colourless utility
 * artifacts — driven end-to-end through the real engine by
 * [ScriptedGame] (which invariant-checks every transition). Nothing here is asserted off a definition:
 * each land is *played* and watched arriving tapped, each mana ability is *spent* on a real spell, the
 * activated ability is *activated* and paid for, and each trigger is allowed to resolve.
 *
 * The two cases that matter most are the ones where a plausible wrong encoding would still look right:
 * Ichor Wellspring's second trigger — the one a single-condition encoding would silently delete — and
 * Expedition Map's land filter finding a land that neither of the two older filters would (an artifact
 * land has no Basic supertype and no land subtype).
 */
class UtilityLandAndArtifactAcceptanceSpec :
    StringSpec({

        "CR 614.1c: each snow dual is on the battlefield tapped the instant it is played" {
            listOf("Glacial Floodplain", "Volatile Fjord").forEach { name ->
                val game = utilityGame(alice = UtilityBoard(hand = listOf(obj(0, name))))
                game.playLand(name)
                game.state.sharedZones.battlefield
                    .single()
                    .tapped
                    .shouldBeTrue()
            }
        }

        "CR 305.6: Glacial Floodplain's Plains half pays a {W} Aura, and the land taps for it" {
            val game =
                utilityGame(
                    alice =
                        UtilityBoard(
                            hand = listOf(obj(0, "Spirit Link")),
                            battlefield =
                                listOf(
                                    notSick(obj(1, "Glacial Floodplain")),
                                    notSick(obj(2, "Grizzly Bears")),
                                ),
                        ),
                )
            game.castTargeting("Spirit Link", Target.Permanent(ObjectId(2)))
            game.settle()

            game.state.sharedZones.battlefield
                .map { it.card } shouldContain CardRef("Spirit Link")
            game.state.sharedZones.battlefield
                .single { it.card == CardRef("Glacial Floodplain") }
                .tapped
                .shouldBeTrue()
        }

        "CR 305.6: Glacial Floodplain's Island half pays a {U} instant" {
            val game =
                utilityGame(
                    alice =
                        UtilityBoard(
                            hand = listOf(obj(0, "Mental Note")),
                            battlefield = listOf(notSick(obj(1, "Glacial Floodplain"))),
                        ),
                )
            game.castOption("Mental Note")
            game.payFirstPlan()
            game.settle()

            game.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContain CardRef("Mental Note")
        }

        "CR 305.6: Volatile Fjord's Mountain half pays a Lightning Bolt, and its Island half a {U} instant" {
            val bolted =
                utilityGame(
                    alice =
                        UtilityBoard(
                            hand = listOf(obj(0, "Lightning Bolt")),
                            battlefield = listOf(notSick(obj(1, "Volatile Fjord"))),
                        ),
                )
            bolted.castTargeting("Lightning Bolt", Target.Player(bob))
            bolted.settle()
            bolted.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - BOLT_DAMAGE

            val noted =
                utilityGame(
                    alice =
                        UtilityBoard(
                            hand = listOf(obj(0, "Mental Note")),
                            battlefield = listOf(notSick(obj(1, "Volatile Fjord"))),
                        ),
                )
            noted.castOption("Mental Note")
            noted.payFirstPlan()
            noted.settle()
            noted.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContain CardRef("Mental Note")
        }

        "CR 603.6a: Ichor Wellspring's enters half draws its controller a card as the artifact arrives" {
            val game =
                utilityGame(
                    alice =
                        UtilityBoard(
                            hand = listOf(obj(0, "Ichor Wellspring")),
                            battlefield = listOf(notSick(obj(1, "Mountain")), notSick(obj(2, "Mountain"))),
                        ),
                )
            val handBefore =
                game.state.players
                    .getValue(alice)
                    .hand
                    .size
            game.castOption("Ichor Wellspring")
            game.payFirstPlan()
            game.settle()

            game.state.sharedZones.battlefield
                .map { it.card } shouldContain CardRef("Ichor Wellspring")
            // One card left the hand (the Wellspring) and one entered it (the trigger's draw).
            game.state.players
                .getValue(alice)
                .hand
                .size shouldBe handBefore
        }

        "CR 603.6b: Ichor Wellspring's dies half draws again — the trigger a one-condition encoding loses" {
            val game =
                utilityGame(
                    alice =
                        UtilityBoard(
                            hand = listOf(obj(0, "Ancient Grudge")),
                            battlefield =
                                listOf(
                                    notSick(obj(1, "Mountain")),
                                    notSick(obj(2, "Mountain")),
                                    notSick(obj(3, "Ichor Wellspring")),
                                ),
                        ),
                )
            val handBefore =
                game.state.players
                    .getValue(alice)
                    .hand
                    .size
            game.castTargeting("Ancient Grudge", Target.Permanent(ObjectId(3)))
            game.settle()

            // The Wellspring was destroyed (CR 701.7a) and its CR 603.6b trigger drew a card.
            game.state.sharedZones.battlefield
                .map { it.card } shouldNotContain CardRef("Ichor Wellspring")
            game.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContain CardRef("Ichor Wellspring")
            // The Grudge left the hand and the trigger's draw replaced it exactly.
            game.state.players
                .getValue(alice)
                .hand
                .size shouldBe handBefore
        }

        "CR 701.18: Expedition Map finds an artifact land — a land neither older filter would offer" {
            val game =
                utilityGame(
                    alice =
                        UtilityBoard(
                            battlefield =
                                listOf(
                                    notSick(obj(0, "Expedition Map")),
                                    notSick(obj(1, "Mountain")),
                                    notSick(obj(2, "Mountain")),
                                ),
                            library = listOf(obj(3, "Great Furnace"), obj(4, "Lightning Bolt")),
                        ),
                )
            game.activateAbility("Expedition Map")
            game.payFirstPlan()
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseFromLibrary }
            val find = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseFromLibrary>()

            // Great Furnace has neither the Basic supertype nor a land subtype, so BASIC_LAND_CARD and
            // ISLAND_CARD would both miss it; a nonland card is never offered by any of the three.
            find.options.map { it.card } shouldContain CardRef("Great Furnace")
            find.options.map { it.card } shouldNotContain CardRef("Lightning Bolt")

            val index = find.options.indexOfFirst { it.card == CardRef("Great Furnace") }
            game.apply(Decision.SingleSelect(find.id, index))
            game.settle()

            game.state.players
                .getValue(alice)
                .hand
                .map { it.card } shouldContain CardRef("Great Furnace")
            // The Map was sacrificed as the cost was paid (CR 602.2b), before the search ever resolved.
            game.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContain CardRef("Expedition Map")
            game.state.sharedZones.battlefield
                .map { it.card } shouldNotContain CardRef("Expedition Map")
        }

        "CR 701.18b: failing to find is always legal, and the library is shuffled either way" {
            val game =
                utilityGame(
                    alice =
                        UtilityBoard(
                            battlefield =
                                listOf(
                                    notSick(obj(0, "Expedition Map")),
                                    notSick(obj(1, "Mountain")),
                                    notSick(obj(2, "Mountain")),
                                ),
                            library = listOf(obj(3, "Great Furnace")),
                        ),
                )
            game.activateAbility("Expedition Map")
            game.payFirstPlan()
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseFromLibrary }
            val find = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseFromLibrary>()

            // The find-none answer is the option count itself (ADR-005: legality *is* the enumeration).
            game.apply(Decision.SingleSelect(find.id, find.options.size))
            game.settle()

            game.state.players
                .getValue(alice)
                .hand
                .map { it.card } shouldNotContain CardRef("Great Furnace")
            game.state.sharedZones.battlefield
                .map { it.card } shouldNotContain CardRef("Expedition Map")
        }
    })

/** What a resolving Lightning Bolt deals (CR 119.3). */
private const val BOLT_DAMAGE: Int = 3

/** The turn these scenarios resume on — late enough that nothing is summoning sick by construction. */
private const val UTILITY_TURN: Int = 3

/** Runaway guard for [driveUntil]. */
private const val MAX_UTILITY_DRIVE_STEPS: Int = 200

/** Spare library cards per seat, so an incidental draw step never decks a scenario out (CR 704.5c). */
private const val SPARE_LIBRARY_CARDS: Int = 6

/** One seat's hand, battlefield, library, and graveyard objects, for constructing a scenario board. */
private data class UtilityBoard(
    val hand: List<GameObject> = emptyList(),
    val battlefield: List<GameObject> = emptyList(),
    val library: List<GameObject> = emptyList(),
    val graveyard: List<GameObject> = emptyList(),
)

/** An object [id] of card [name] (its owner is reassigned per seat by [utilityGame]). */
private fun obj(
    id: Long,
    name: String,
): GameObject = GameObject(ObjectId(id), CardRef(name), alice)

/** Marks a battlefield permanent as no longer summoning sick (controlled since the turn began). */
private fun notSick(obj: GameObject): GameObject = obj.copy(summoningSick = false)

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

/** Selects the activate-ability option for [name] from the current priority window (CR 602.2). */
private fun ScriptedGame.activateAbility(name: String): ScriptedGame {
    val window = action()
    val index = window.options.indexOfFirst { it is PriorityOption.ActivateAbility && it.card == CardRef(name) }
    check(index >= 0) { "no ActivateAbility option for $name in ${window.options}" }
    return apply(Decision.SingleSelect(window.id, index))
}

/** Answers the pending payment request with its first enumerated plan (CR 601.2g). */
private fun ScriptedGame.payFirstPlan(): ScriptedGame {
    val payment = pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
    return apply(Decision.SingleSelect(payment.id, 0))
}

/** Casts the targeted spell [name] at [target], paying its first plan (CR 601.2c, CR 601.2g). */
private fun ScriptedGame.castTargeting(
    name: String,
    target: Target,
): ScriptedGame {
    castOption(name)
    val targets = pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
    val index = targets.options.indexOf(target)
    check(index >= 0) { "no legal target $target for $name in ${targets.options}" }
    apply(Decision.SingleSelect(targets.id, index))
    return payFirstPlan()
}

/** Advances until the stack is empty **and** no trigger is still waiting to be put on it (CR 603.3b). */
private fun ScriptedGame.settle(): ScriptedGame =
    driveUntil { state.sharedZones.stack.isEmpty() && state.pendingTriggers.isEmpty() }

/** Advances (passing / declining combat / ordering triggers) until [predicate] holds. */
private fun ScriptedGame.driveUntil(predicate: () -> Boolean): ScriptedGame {
    var steps = 0
    while (!predicate() && !isOver && steps < MAX_UTILITY_DRIVE_STEPS) {
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
            else -> error("driveUntil cannot answer $request")
        }
        steps++
    }
    check(predicate()) { "the drive predicate was not satisfied within $MAX_UTILITY_DRIVE_STEPS steps" }
    return this
}

/**
 * A [ScriptedGame] resumed from a handcrafted precombat-main state (ADR-004) over the real [MvpCards]
 * definitions: alice holds priority on turn [UTILITY_TURN] with the given boards, and both seats keep a
 * small filler library so a turn walk never decks anyone out. Every transition is invariant-checked.
 */
private fun utilityGame(
    alice: UtilityBoard = UtilityBoard(),
    bob: UtilityBoard = UtilityBoard(),
): ScriptedGame {
    val aliceSeat = dev.mtgplay.acceptance.alice
    val bobSeat = dev.mtgplay.acceptance.bob
    val bobHand = bob.hand.map { it.copy(owner = bobSeat) }
    val bobField = bob.battlefield.map { it.copy(owner = bobSeat) }
    val bobLibrary = bob.library.map { it.copy(owner = bobSeat) }
    val bobGrave = bob.graveyard.map { it.copy(owner = bobSeat) }
    val placed =
        alice.hand + alice.battlefield + alice.library + alice.graveyard +
            bobHand + bobField + bobLibrary + bobGrave
    var nextId = (placed.maxOfOrNull { it.id.value } ?: -1L) + 1

    fun padding(owner: PlayerId): List<GameObject> =
        List(SPARE_LIBRARY_CARDS) { GameObject(ObjectId(nextId++), CardRef("Mountain"), owner) }

    val aliceLibrary = alice.library + padding(aliceSeat)
    val paddedBobLibrary = bobLibrary + padding(bobSeat)

    fun seat(
        seatId: PlayerId,
        hand: List<GameObject>,
        library: List<GameObject>,
        graveyard: List<GameObject>,
    ) = PlayerState(
        life = STARTING_LIFE,
        library = library.toPersistentList(),
        hand = hand.toPersistentList(),
        graveyard = graveyard.toPersistentList(),
        priorityStatus = if (seatId == aliceSeat) PriorityStatus.HOLDS_PRIORITY else PriorityStatus.NONE,
    )

    val state =
        GameState(
            players =
                persistentMapOf(
                    aliceSeat to seat(aliceSeat, alice.hand, aliceLibrary, alice.graveyard),
                    bobSeat to seat(bobSeat, bobHand, paddedBobLibrary, bobGrave),
                ),
            turn = Turn(aliceSeat, UTILITY_TURN, TurnPhase.PRECOMBAT_MAIN, null),
            sharedZones =
                SharedZones(
                    battlefield = (alice.battlefield + bobField).toPersistentList(),
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
