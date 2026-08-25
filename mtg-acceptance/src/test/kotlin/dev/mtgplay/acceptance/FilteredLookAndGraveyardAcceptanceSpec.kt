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
import dev.mtgplay.rules.viewFor
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The `W7-C` cards driven end-to-end through the real engine by [ScriptedGame], which invariant-checks
 * every transition: the three filtered looks (Ancient Stirrings, Augur of Bolas, Lead the Stampede) and the
 * two graveyard lands (Bojuka Bog, Haunted Fengraf).
 *
 * Three things are only provable here, in a real game, rather than off a definition:
 * 1. **A filtered look's partial publicity.** The kept card is revealed to everyone (CR 701.16a) while the
 *    bottomed cards stay in the looking seat's head (CR 701.14a) — one seat view showing each half.
 * 2. **A played land fires its enters-the-battlefield triggers** (CR 603.6a). Bojuka Bog is the first
 *    encoded land with one, so it is the first card that could ever have observed the triage's **T18**
 *    defect; the assertion here is what stops that fix from silently regressing.
 * 3. **The random return is seeded** (ADR-006). Haunted Fengraf's card comes back from the match-owned
 *    PRNG, so the seed is pinned and the returned card asserted by name.
 */
class FilteredLookAndGraveyardAcceptanceSpec :
    StringSpec({

        "CR 701.16a: Ancient Stirrings keeps a colorless card, reveals only it, and bottoms the rest" {
            val game =
                graveyardGame(
                    board =
                        SeatBoard(
                            hand = listOf(obj(10, "Ancient Stirrings")),
                            battlefield = listOf(obj(0, "Forest")),
                            // Two colorless finds and one green creature that the filter must exclude.
                            library =
                                listOf(
                                    obj(20, "Expedition Map"),
                                    obj(21, "Grizzly Bears"),
                                    obj(22, "Great Furnace"),
                                ),
                        ),
                )
            game.castOption("Ancient Stirrings").payFirstPlan()
            val arrange = game.arrangement()
            val names = arrange.pool.map { it.card.name }
            names shouldContainExactly listOf("Expedition Map", "Grizzly Bears", "Great Furnace")
            // Only the two colorless cards are ever offered to the hand; the green Bears never is.
            arrange.options.flatMap { it.toHand }.toSet() shouldBe setOf(0, 2)

            // Keep the Map, bottom the Bears above the Furnace.
            game.chooseArrangement(hand = listOf(0), top = emptyList(), bottom = listOf(1, 2))
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }

            game.handNames(alice) shouldContainExactly listOf("Expedition Map")
            game.libraryNames(alice) shouldContainExactly listOf("Grizzly Bears", "Great Furnace")
            // CR 701.16a for the keep, and nothing else: the two bottomed cards are never revealed.
            game.state.events
                .filterIsInstance<GameEvent.CardsRevealed>()
                .single()
                .cards
                .map { it.name } shouldContainExactly listOf("Expedition Map")
        }

        "CR 701.14a: Ancient Stirrings' bottomed cards stay private to the looking seat" {
            val game =
                graveyardGame(
                    board =
                        SeatBoard(
                            hand = listOf(obj(10, "Ancient Stirrings")),
                            battlefield = listOf(obj(0, "Forest")),
                            library = listOf(obj(20, "Expedition Map"), obj(21, "Grizzly Bears")),
                        ),
                )
            game.castOption("Ancient Stirrings").payFirstPlan()
            game.arrangement()

            // Mid-look, both cards are hidden from the opponent — the pause is where a leak would show.
            val theirs = viewFor(game.state, bob)
            theirs.cards.keys.map { it.name } shouldNotContain "Expedition Map"
            theirs.cards.keys.map { it.name } shouldNotContain "Grizzly Bears"

            // Decline the keep, bottoming both; still nothing is revealed, and the library is intact.
            game.chooseArrangement(hand = emptyList(), top = emptyList(), bottom = listOf(1, 0))
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            game.state.events
                .filterIsInstance<GameEvent.CardsRevealed>()
                .shouldBeEmpty()
            game.handNames(alice).shouldBeEmpty()
            game.libraryNames(alice) shouldContainExactly listOf("Grizzly Bears", "Expedition Map")
        }

        "CR 701.16a: Lead the Stampede takes every creature it finds in one decision" {
            val game =
                graveyardGame(
                    board =
                        SeatBoard(
                            hand = listOf(obj(10, "Lead the Stampede")),
                            battlefield =
                                listOf(obj(0, "Forest"), obj(1, "Forest"), obj(2, "Forest")),
                            library =
                                listOf(
                                    obj(20, "Grizzly Bears"),
                                    obj(21, "Lightning Bolt"),
                                    obj(22, "Elvish Mystic"),
                                ),
                        ),
                )
            game.castOption("Lead the Stampede").payFirstPlan()
            val arrange = game.arrangement()
            arrange.options.flatMap { it.toHand }.toSet() shouldBe setOf(0, 2)
            // "Any number": taking both creatures at once is one enumerated option, not two rounds.
            game.chooseArrangement(hand = listOf(0, 2), top = emptyList(), bottom = listOf(1))
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }

            game.handNames(alice) shouldContainExactly listOf("Grizzly Bears", "Elvish Mystic")
            game.libraryNames(alice) shouldContainExactly listOf("Lightning Bolt")
            game.state.events
                .filterIsInstance<GameEvent.CardsRevealed>()
                .single()
                .cards
                .map { it.name } shouldContainExactly listOf("Grizzly Bears", "Elvish Mystic")
        }

        "CR 603.6a: Augur of Bolas' enters trigger looks three deep and keeps an instant" {
            val game =
                graveyardGame(
                    board =
                        SeatBoard(
                            hand = listOf(obj(10, "Augur of Bolas")),
                            battlefield = listOf(obj(0, "Island"), obj(1, "Island")),
                            library =
                                listOf(obj(20, "Grizzly Bears"), obj(21, "Lightning Bolt"), obj(22, "Rancor")),
                        ),
                )
            game.castOption("Augur of Bolas").payFirstPlan()
            val arrange = game.arrangement()
            arrange.pool.map { it.card.name } shouldContainExactly
                listOf("Grizzly Bears", "Lightning Bolt", "Rancor")
            // The filter is instant-or-sorcery, so only the Bolt is keepable.
            arrange.options.flatMap { it.toHand }.toSet() shouldBe setOf(1)

            game.chooseArrangement(hand = listOf(1), top = emptyList(), bottom = listOf(0, 2))
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }

            game.handNames(alice) shouldContainExactly listOf("Lightning Bolt")
            game.libraryNames(alice) shouldContainExactly listOf("Grizzly Bears", "Rancor")
            // The creature is on the battlefield — the trigger resolved after the permanent entered.
            game.state.sharedZones.battlefield
                .map { it.card.name } shouldContainExactly listOf("Island", "Island", "Augur of Bolas")
        }

        "CR 603.6a: a *played* Bojuka Bog fires its enters trigger and exiles the target graveyard (T18)" {
            val game =
                graveyardGame(
                    board = SeatBoard(hand = listOf(obj(10, "Bojuka Bog"))),
                    bobGraveyard = listOf(obj(50, "Grizzly Bears"), obj(51, "Lightning Bolt")),
                )
            game.playLand("Bojuka Bog")
            // The land is played, not cast (CR 305.1) — and the trigger fires anyway, which is the whole
            // point: this transition used to narrate the entry and silently skip the triggers.
            val targets = game.targetChoice()
            val index = targets.options.indexOf(Target.Player(bob))
            check(index >= 0) { "CR 115.1a: both players must be legal targets, got ${targets.options}" }
            game.apply(Decision.SingleSelect(targets.id, index))
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }

            game.state.players
                .getValue(bob)
                .graveyard
                .shouldBeEmpty()
            game.state.sharedZones.exile
                .map { it.card.name } shouldContainExactly listOf("Grizzly Bears", "Lightning Bolt")
            // CR 614.1c and CR 603.6a both apply — the Bog is on the battlefield and it is tapped.
            game.state.sharedZones.battlefield
                .single { it.card == CardRef("Bojuka Bog") }
                .tapped shouldBe true
        }

        "ADR-006: Haunted Fengraf returns a creature card chosen by the match PRNG" {
            val game =
                graveyardGame(
                    board =
                        SeatBoard(
                            battlefield =
                                listOf(
                                    obj(0, "Haunted Fengraf"),
                                    obj(1, "Mountain"),
                                    obj(2, "Mountain"),
                                    obj(3, "Mountain"),
                                ),
                            graveyard =
                                listOf(
                                    obj(40, "Grizzly Bears"),
                                    obj(41, "Lightning Bolt"),
                                    obj(42, "Hill Giant"),
                                ),
                        ),
                    seed = FENGRAF_SEED,
                )
            game.activateAbility("Haunted Fengraf").payFirstPlan()
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }

            // The seed is pinned, so the card is a contract rather than a coin flip; the Bolt is never
            // eligible, and the Fengraf itself is a land, so it never returns itself.
            game.handNames(alice) shouldContainExactly listOf("Hill Giant")
            game.state.players
                .getValue(alice)
                .graveyard
                .map { it.card.name } shouldContainExactly
                listOf("Grizzly Bears", "Lightning Bolt", "Haunted Fengraf")
            // {3} + {T} + sacrifice: the land paid its own way off the battlefield.
            game.state.sharedZones.battlefield
                .map { it.card.name } shouldContainExactly listOf("Mountain", "Mountain", "Mountain")
        }
    })

// ---- driving helpers over ScriptedGame (invariant-checked every transition) -----------------------

/** The seed these Fengraf scenarios pin; with three creature cards eligible it selects the third. */
private const val FENGRAF_SEED: Long = 1

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

/** Takes the CR 116.2a play-land special action for [name] from the current priority window. */
private fun ScriptedGame.playLand(name: String): ScriptedGame {
    val window = action()
    val index = window.options.indexOfFirst { it is PriorityOption.PlayLand && it.card == CardRef(name) }
    check(index >= 0) { "no PlayLand option for $name in ${window.options}" }
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

/** The pending target choice (CR 603.3d for a trigger), driving priority passes until it opens. */
private fun ScriptedGame.targetChoice(): DecisionRequest.ChooseTargets {
    driveUntil { pendingRequest is DecisionRequest.ChooseTargets }
    return pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
}

/** The pending arrangement request (CR 701.14a), driving priority passes until the clause opens it. */
private fun ScriptedGame.arrangement(): DecisionRequest.ChooseLibraryArrangement {
    driveUntil { pendingRequest is DecisionRequest.ChooseLibraryArrangement }
    return pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseLibraryArrangement>()
}

/** Answers the pending arrangement with the named destination lists, which must be an enumerated option. */
private fun ScriptedGame.chooseArrangement(
    hand: List<Int>,
    top: List<Int>,
    bottom: List<Int>,
): ScriptedGame {
    val request = arrangement()
    val wanted = DecisionRequest.ChooseLibraryArrangement.Option(hand, top, bottom)
    val index = request.options.indexOf(wanted)
    check(index >= 0) { "the arrangement $wanted is not enumerated among ${request.options}" }
    return apply(Decision.SingleSelect(request.id, index))
}

private fun ScriptedGame.handNames(seat: PlayerId) =
    state.players
        .getValue(seat)
        .hand
        .map { it.card.name }

private fun ScriptedGame.libraryNames(seat: PlayerId) =
    state.players
        .getValue(seat)
        .library
        .map { it.card.name }

/** Passes priority, ordering any simultaneous triggers in the deterministic identity permutation. */
private fun ScriptedGame.passOrOrder(): ScriptedGame =
    when (val request = pendingRequest) {
        is DecisionRequest.ChooseAction -> {
            val index = request.options.indexOfFirst { it is PriorityOption.Pass }
            check(index >= 0) { "CR 117.3d: passing must always be enumerated" }
            apply(Decision.SingleSelect(request.id, index))
        }
        is DecisionRequest.OrderTriggers -> apply(Decision.MultiSelect(request.id, request.options.indices.toList()))
        is DecisionRequest.DeclareAttackers -> apply(Decision.MultiSelect(request.id, emptyList()))
        is DecisionRequest.DeclareBlockers -> apply(Decision.MultiSelect(request.id, emptyList()))
        else -> error("passOrOrder cannot answer $request")
    }

/** Advances (passing / declining combat / ordering triggers) until [predicate] holds. */
private fun ScriptedGame.driveUntil(predicate: () -> Boolean): ScriptedGame {
    var steps = 0
    while (!predicate() && !isOver && steps < MAX_DRIVE_STEPS) {
        passOrOrder()
        steps++
    }
    check(predicate()) { "the drive predicate was not satisfied within $MAX_DRIVE_STEPS steps" }
    return this
}

private const val MAX_DRIVE_STEPS: Int = 200

// ---- state construction ---------------------------------------------------------------------------

/** The turn these scenarios resume on — late enough that nothing is summoning sick by construction. */
private const val SCENARIO_TURN: Int = 3

/** One seat's zones, for constructing a scenario board. */
private data class SeatBoard(
    val hand: List<GameObject> = emptyList(),
    val battlefield: List<GameObject> = emptyList(),
    val library: List<GameObject> = emptyList(),
    val graveyard: List<GameObject> = emptyList(),
)

/** A zone object [id] of card [name] (owner reassigned per seat by [graveyardGame]). */
private fun obj(
    id: Long,
    name: String,
): GameObject = GameObject(ObjectId(id), CardRef(name), alice)

/**
 * A [ScriptedGame] resumed from a handcrafted precombat-main state (ADR-004): alice holds priority on
 * [alice]'s board over the real [MvpCards] definitions, on turn [SCENARIO_TURN], which is hers. Bob keeps a
 * small library so no draw-from-empty loss (CR 704.5c) ends a game mid-scenario, plus [bobGraveyard] for the
 * graveyard-exile cases.
 */
private fun graveyardGame(
    board: SeatBoard,
    bobGraveyard: List<GameObject> = emptyList(),
    seed: Long = 7,
): ScriptedGame {
    val aliceSeat = dev.mtgplay.acceptance.alice
    val bobSeat = dev.mtgplay.acceptance.bob
    val bobLibrary = listOf(obj(90, "Mountain"), obj(91, "Mountain")).map { it.copy(owner = bobSeat) }
    val bobYard = bobGraveyard.map { it.copy(owner = bobSeat) }
    val allObjects =
        board.hand + board.battlefield + board.library + board.graveyard + bobLibrary + bobYard
    val nextId = (allObjects.maxOfOrNull { it.id.value } ?: -1L) + 1
    val state =
        GameState(
            players =
                persistentMapOf(
                    aliceSeat to
                        PlayerState(
                            life = STARTING_LIFE,
                            library = board.library.toPersistentList(),
                            hand = board.hand.toPersistentList(),
                            graveyard = board.graveyard.toPersistentList(),
                            priorityStatus = PriorityStatus.HOLDS_PRIORITY,
                        ),
                    bobSeat to
                        PlayerState(
                            life = STARTING_LIFE,
                            library = bobLibrary.toPersistentList(),
                            hand = persistentListOf(),
                            graveyard = bobYard.toPersistentList(),
                        ),
                ),
            turn = Turn(aliceSeat, SCENARIO_TURN, TurnPhase.PRECOMBAT_MAIN, null),
            sharedZones =
                SharedZones(
                    battlefield = board.battlefield.toPersistentList(),
                    stack = persistentListOf(),
                    exile = persistentListOf(),
                ),
            nextObjectId = nextId,
            rng = Rng(seed),
            events = persistentListOf(),
            definitions = MvpCards.definitions.toPersistentMap(),
        )
    return ScriptedGame.startFrom(state)
}
