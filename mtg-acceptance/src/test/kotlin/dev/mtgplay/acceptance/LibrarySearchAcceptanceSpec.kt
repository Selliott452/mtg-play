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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The library-search packet's six cards driven end-to-end through the real engine by [ScriptedGame]
 * (which invariant-checks every transition). Nothing here is asserted off a definition: every ability
 * is really activated and paid for, every search really pauses for its find-one choice, and every
 * permanent is watched arriving on the battlefield.
 *
 * The cases that matter most are the ones a plausible wrong encoding would still pass:
 * - the Landscape's fetch **must not** offer Idyllic Beachfront, which has the Island land type but not
 *   the Basic supertype — the discriminator between `basicOneOf` and a bare land-type filter;
 * - Crop Rotation's find **must** enter untapped, *unless* the found land's own CR 614.1c clause says
 *   otherwise, which is the difference between a destination and a replacement effect;
 * - Generous Ent's forestcycling **must** offer Gingerbread Cabin, a nonbasic Forest — the discriminator
 *   between typecycling (CR 702.28b, a subtype) and basic landcycling (a supertype);
 * - Lembas' shuffle-in **must** leave the graveyard empty and the library one larger, which a
 *   "return to hand" or a "put on top" mis-encoding would both fail.
 */
class LibrarySearchAcceptanceSpec :
    StringSpec({

        "CR 205.4 + CR 205.3b: a Landscape's fetch offers only basics of its three named land types" {
            val game =
                searchGame(
                    alice =
                        SearchBoard(
                            battlefield = listOf(notSick(obj(0, "Contaminated Landscape"))),
                            library =
                                listOf(
                                    obj(1, "Plains"),
                                    obj(2, "Island"),
                                    obj(3, "Swamp"),
                                    obj(4, "Mountain"),
                                    obj(5, "Idyllic Beachfront"),
                                ),
                        ),
                )
            game.activateAbility("Contaminated Landscape")
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseFromLibrary }
            val find = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseFromLibrary>()

            // Mountain is a basic of the wrong type; Idyllic Beachfront has the Island land type but no
            // Basic supertype, so a bare ISLAND_CARD filter would wrongly offer it.
            find.options.map { it.card } shouldContainExactlyInAnyOrder
                listOf(CardRef("Plains"), CardRef("Island"), CardRef("Swamp"))
        }

        "CR 110.5b: a Landscape's find enters the battlefield tapped, and the land itself is sacrificed" {
            val game =
                searchGame(
                    alice =
                        SearchBoard(
                            battlefield = listOf(notSick(obj(0, "Twisted Landscape"))),
                            library = listOf(obj(1, "Forest"), obj(2, "Island")),
                        ),
                )
            game.activateAbility("Twisted Landscape")
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseFromLibrary }
            val find = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseFromLibrary>()
            // Island is not one of Twisted Landscape's three types (Swamp, Mountain, Forest).
            find.options.map { it.card } shouldContainExactlyInAnyOrder listOf(CardRef("Forest"))
            game.apply(Decision.SingleSelect(find.id, 0))
            game.settle()

            val forest =
                game.state.sharedZones.battlefield
                    .single { it.card == CardRef("Forest") }
            forest.tapped.shouldBeTrue()
            // Both cost components were paid on activation (CR 602.2b), so the land was already gone.
            game.state.sharedZones.battlefield
                .map { it.card } shouldNotContain CardRef("Twisted Landscape")
            game.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContain CardRef("Twisted Landscape")
        }

        "CR 702.29a: cycling a Landscape discards it and draws a card — the plain, non-search cycling" {
            val game =
                searchGame(
                    alice =
                        SearchBoard(
                            hand = listOf(obj(0, "Perilous Landscape")),
                            battlefield =
                                listOf(
                                    notSick(obj(1, "Island")),
                                    notSick(obj(2, "Mountain")),
                                    notSick(obj(3, "Plains")),
                                ),
                            library = listOf(obj(4, "Lightning Bolt")),
                        ),
                )
            val handBefore =
                game.state.players
                    .getValue(alice)
                    .hand.size
            game.activateAbility("Perilous Landscape")
            game.payFirstPlan()
            game.settle()

            // CR 702.29a is "draw a card", not a search: no find-one pause ever opened.
            game.state.players
                .getValue(alice)
                .hand
                .map { it.card } shouldContain CardRef("Lightning Bolt")
            // The Landscape left the hand as the cost was paid and the draw refilled the slot exactly.
            game.state.players
                .getValue(alice)
                .hand
                .size shouldBe handBefore
            game.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContain CardRef("Perilous Landscape")
        }

        "CR 702.28b: forestcycling finds a **nonbasic** Forest — typecycling names a subtype, not the basic land" {
            val game =
                searchGame(
                    alice =
                        SearchBoard(
                            hand = listOf(obj(0, "Generous Ent")),
                            battlefield = listOf(notSick(obj(1, "Mountain"))),
                            library =
                                listOf(obj(2, "Gingerbread Cabin"), obj(3, "Forest"), obj(4, "Island")),
                        ),
                )
            game.activateAbility("Generous Ent")
            game.payFirstPlan()
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseFromLibrary }
            val find = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseFromLibrary>()

            // Gingerbread Cabin is `Land — Forest` with no Basic supertype: BASIC_LAND_CARD would miss it.
            find.options.map { it.card } shouldContainExactlyInAnyOrder
                listOf(CardRef("Gingerbread Cabin"), CardRef("Forest"))

            val index = find.options.indexOfFirst { it.card == CardRef("Gingerbread Cabin") }
            game.apply(Decision.SingleSelect(find.id, index))
            game.settle()

            game.state.players
                .getValue(alice)
                .hand
                .map { it.card } shouldContain CardRef("Gingerbread Cabin")
            game.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContain CardRef("Generous Ent")
        }

        "CR 701.18: Crop Rotation sacrifices a land as a cost, then puts its find onto the battlefield untapped" {
            val game =
                searchGame(
                    alice =
                        SearchBoard(
                            hand = listOf(obj(0, "Crop Rotation")),
                            battlefield = listOf(notSick(obj(1, "Forest")), notSick(obj(2, "Mountain"))),
                            library = listOf(obj(3, "Great Furnace"), obj(4, "Lightning Bolt")),
                        ),
                )
            game.castOption("Crop Rotation")
            val sacrifice = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseSacrificesForCost>()
            val mountainIndex = sacrifice.options.indexOfFirst { it.card == CardRef("Mountain") }
            game.apply(Decision.MultiSelect(sacrifice.id, listOf(mountainIndex)))
            game.payFirstPlan()
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseFromLibrary }
            val find = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseFromLibrary>()

            // "A land card" is the widest filter: an artifact land with no supertype and no land type.
            find.options.map { it.card } shouldContainExactlyInAnyOrder listOf(CardRef("Great Furnace"))
            game.apply(Decision.SingleSelect(find.id, 0))
            game.settle()

            // CR 110.5a: the card does not say "tapped", so the found land arrives untapped.
            val furnace =
                game.state.sharedZones.battlefield
                    .single { it.card == CardRef("Great Furnace") }
            furnace.tapped.shouldBeFalse()
            // The sacrifice was a cost (CR 601.2h), paid before the spell ever resolved.
            game.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContain CardRef("Mountain")
        }

        "CR 614.1c: a found land's own enters-tapped clause still applies — a moving effect does not overrule it" {
            val game =
                searchGame(
                    alice =
                        SearchBoard(
                            hand = listOf(obj(0, "Crop Rotation")),
                            battlefield = listOf(notSick(obj(1, "Forest")), notSick(obj(2, "Mountain"))),
                            library = listOf(obj(3, "Drossforge Bridge")),
                        ),
                )
            game.castOption("Crop Rotation")
            val sacrifice = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseSacrificesForCost>()
            game.apply(
                Decision.MultiSelect(
                    sacrifice.id,
                    listOf(sacrifice.options.indexOfFirst { it.card == CardRef("Mountain") }),
                ),
            )
            game.payFirstPlan()
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseFromLibrary }
            val find = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseFromLibrary>()
            game.apply(Decision.SingleSelect(find.id, 0))
            game.settle()

            // Drossforge Bridge prints "This land enters tapped"; Crop Rotation does not say "untapped".
            game.state.sharedZones.battlefield
                .single { it.card == CardRef("Drossforge Bridge") }
                .tapped
                .shouldBeTrue()
        }

        "CR 701.18b: failing to find a Landscape's basic is legal, and the land is sacrificed either way" {
            val game =
                searchGame(
                    alice =
                        SearchBoard(
                            battlefield = listOf(notSick(obj(0, "Contaminated Landscape"))),
                            library = listOf(obj(1, "Island")),
                        ),
                )
            game.activateAbility("Contaminated Landscape")
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseFromLibrary }
            val find = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseFromLibrary>()
            // ADR-005: the find-none answer is the option count itself — legality *is* the enumeration.
            game.apply(Decision.SingleSelect(find.id, find.options.size))
            game.settle()

            game.state.sharedZones.battlefield
                .map { it.card } shouldNotContain CardRef("Island")
            game.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContain CardRef("Contaminated Landscape")
        }

        "CR 603.6b + CR 701.20: Lembas eaten from the battlefield gains 3 and shuffles itself back in" {
            val game =
                searchGame(
                    alice =
                        SearchBoard(
                            battlefield =
                                listOf(
                                    notSick(obj(0, "Lembas")),
                                    notSick(obj(1, "Mountain")),
                                    notSick(obj(2, "Forest")),
                                ),
                            library = listOf(obj(3, "Lightning Bolt")),
                        ),
                )
            val lifeBefore =
                game.state.players
                    .getValue(alice)
                    .life
            val libraryBefore =
                game.state.players
                    .getValue(alice)
                    .library.size
            game.activateAbility("Lembas")
            game.payFirstPlan()
            game.settle()

            game.state.players
                .getValue(alice)
                .life shouldBe lifeBefore + LEMBAS_LIFE_GAIN
            // The artifact was sacrificed to its own cost, its dies trigger fired, and the trigger put it
            // back into the library — so it is in neither the graveyard nor the battlefield.
            game.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldNotContain CardRef("Lembas")
            game.state.sharedZones.battlefield
                .map { it.card } shouldNotContain CardRef("Lembas")
            game.state.players
                .getValue(alice)
                .library
                .map { it.card } shouldContain CardRef("Lembas")
            game.state.players
                .getValue(alice)
                .library
                .size shouldBe libraryBefore + 1
        }

        "CR 701.17a: Lembas entering the battlefield scries 1 and then draws" {
            val game =
                searchGame(
                    alice =
                        SearchBoard(
                            hand = listOf(obj(0, "Lembas")),
                            battlefield = listOf(notSick(obj(1, "Mountain")), notSick(obj(2, "Forest"))),
                            library = listOf(obj(3, "Lightning Bolt")),
                        ),
                )
            val handBefore =
                game.state.players
                    .getValue(alice)
                    .hand.size
            game.castOption("Lembas")
            game.payFirstPlan()
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseLibraryArrangement }
            val arrangement = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseLibraryArrangement>()
            game.apply(Decision.SingleSelect(arrangement.id, 0))
            game.settle()

            // The Lembas left the hand for the battlefield and the trigger's draw replaced it exactly.
            game.state.sharedZones.battlefield
                .map { it.card } shouldContain CardRef("Lembas")
            game.state.players
                .getValue(alice)
                .hand
                .size shouldBe handBefore
        }
    })

/** The life Lembas' sacrifice ability gains (CR 120.1). */
private const val LEMBAS_LIFE_GAIN: Int = 3

/** The turn these scenarios resume on — late enough that nothing is summoning sick by construction. */
private const val SEARCH_TURN: Int = 3

/** Runaway guard for [driveUntil]. */
private const val MAX_SEARCH_DRIVE_STEPS: Int = 200

/** Spare library cards per seat, so an incidental draw step never decks a scenario out (CR 704.5c). */
private const val SPARE_LIBRARY_CARDS: Int = 6

/**
 * What the spare library cards are, and it has to be a **nonland**: every scenario here searches for a
 * land, so land filler would silently join every find-one option list and make each assertion about
 * *which* cards a filter offers untestable.
 */
private const val LIBRARY_FILLER: String = "Lightning Bolt"

/** One seat's hand, battlefield, library, and graveyard objects, for constructing a scenario board. */
private data class SearchBoard(
    val hand: List<GameObject> = emptyList(),
    val battlefield: List<GameObject> = emptyList(),
    val library: List<GameObject> = emptyList(),
    val graveyard: List<GameObject> = emptyList(),
)

/** An object [id] of card [name] (its owner is reassigned per seat by [searchGame]). */
private fun obj(
    id: Long,
    name: String,
): GameObject = GameObject(ObjectId(id), CardRef(name), alice)

/** Marks a battlefield permanent as no longer summoning sick (controlled since the turn began). */
private fun notSick(obj: GameObject): GameObject = obj.copy(summoningSick = false)

/** The current priority window, which must be a [DecisionRequest.ChooseAction] (CR 117). */
private fun ScriptedGame.action(): DecisionRequest.ChooseAction =
    pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()

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

/** Advances until the stack is empty **and** no trigger is still waiting to be put on it (CR 603.3b). */
private fun ScriptedGame.settle(): ScriptedGame =
    driveUntil { state.sharedZones.stack.isEmpty() && state.pendingTriggers.isEmpty() }

/** Advances (passing / declining combat / ordering triggers / taking the first arrangement) until [predicate]. */
private fun ScriptedGame.driveUntil(predicate: () -> Boolean): ScriptedGame {
    var steps = 0
    while (!predicate() && !isOver && steps < MAX_SEARCH_DRIVE_STEPS) {
        when (val request = pendingRequest) {
            is DecisionRequest.ChooseAction -> {
                val index = request.options.indexOfFirst { it is PriorityOption.Pass }
                check(index >= 0) { "CR 117.3d: passing must always be enumerated" }
                apply(Decision.SingleSelect(request.id, index))
            }

            is DecisionRequest.OrderTriggers ->
                apply(Decision.MultiSelect(request.id, request.options.indices.toList()))

            is DecisionRequest.ChooseLibraryArrangement -> apply(Decision.SingleSelect(request.id, 0))
            is DecisionRequest.DeclareAttackers -> apply(Decision.MultiSelect(request.id, emptyList()))
            is DecisionRequest.DeclareBlockers -> apply(Decision.MultiSelect(request.id, emptyList()))
            else -> error("driveUntil cannot answer $request")
        }
        steps++
    }
    check(predicate()) { "the drive predicate was not satisfied within $MAX_SEARCH_DRIVE_STEPS steps" }
    return this
}

/**
 * A [ScriptedGame] resumed from a handcrafted precombat-main state (ADR-004) over the real [MvpCards]
 * definitions: alice holds priority on turn [SEARCH_TURN] with the given boards, and both seats keep a
 * small filler library so a turn walk never decks anyone out. Every transition is invariant-checked.
 *
 * The seed is fixed at `0`, which is what makes every search's mandatory CR 701.18 shuffle — and
 * Lembas' CR 701.20 one — reproducible across runs (ADR-006). No assertion here reads a post-shuffle
 * library *order*; the known-answer pin for that lives in `ShuffleIntoLibrarySpec`.
 */
private fun searchGame(
    alice: SearchBoard = SearchBoard(),
    bob: SearchBoard = SearchBoard(),
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
        List(SPARE_LIBRARY_CARDS) { GameObject(ObjectId(nextId++), CardRef(LIBRARY_FILLER), owner) }

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
            turn = Turn(aliceSeat, SEARCH_TURN, TurnPhase.PRECOMBAT_MAIN, null),
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
