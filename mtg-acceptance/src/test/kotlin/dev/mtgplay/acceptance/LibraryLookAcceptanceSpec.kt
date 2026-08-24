package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.definition.LibraryLookSource
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
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.DecisionRequestKind
import dev.mtgplay.rules.DecisionView
import dev.mtgplay.rules.PendingLibraryLookView
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.viewFor
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * The four `FW-LIBLOOK` demonstration cards, each driven end-to-end through the real engine by
 * [ScriptedGame] (which invariant-checks every transition): Preordain's scry (CR 701.17a), Ponder's
 * reorder plus its seeded optional shuffle (ADR-006), Impulse's **mandatory** keep and ordered bottoming,
 * and Brainstorm's ordered placement from the hand (CR 400.7). These are the four cards the card-selection
 * packet dropped by name for want of this framework (docs/design/library-look.md §9), so they are its
 * proof; nothing here is asserted off a definition.
 */
class LibraryLookAcceptanceSpec :
    StringSpec({

        "CR 701.17a: Preordain scries two — the chosen split and order stand, then it draws" {
            val game =
                lookGame(
                    alice =
                        LookBoard(
                            hand = listOf(obj(10, "Preordain")),
                            battlefield = listOf(obj(0, "Island")),
                            library =
                                listOf(obj(20, "Grizzly Bears"), obj(21, "Lightning Bolt"), obj(22, "Rancor")),
                        ),
                )
            game.castOption("Preordain").payFirstPlan()
            val arrange = game.arrangement()
            arrange.pool.map { it.card.name } shouldContainExactly listOf("Grizzly Bears", "Lightning Bolt")
            // (2 + 1)! outcomes: the free partition and the order within each group (CR 701.17a).
            arrange.options.size shouldBe 6
            // Bottom the Bolt, keep the Bear on top; the draw then takes the Bear.
            game.chooseArrangement(hand = emptyList(), top = listOf(0), bottom = listOf(1))
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }

            game.handNames(alice) shouldContainExactly listOf("Grizzly Bears")
            game.libraryNames(alice) shouldContainExactly listOf("Rancor", "Lightning Bolt")
            // CR 701.14a vs CR 701.16a: a scry looks, it does not reveal.
            game.state.events
                .filterIsInstance<GameEvent.CardsRevealed>()
                .shouldBeEmpty()
            game.state.events
                .filterIsInstance<GameEvent.CardsLookedAt>()
                .single()
                .count shouldBe 2
        }

        "CR 701.14a: Preordain's looked-at cards reach the scrying seat and no one else" {
            val game =
                lookGame(
                    alice =
                        LookBoard(
                            hand = listOf(obj(10, "Preordain")),
                            battlefield = listOf(obj(0, "Island")),
                            library = listOf(obj(20, "Grizzly Bears"), obj(21, "Lightning Bolt")),
                        ),
                )
            game.castOption("Preordain").payFirstPlan()
            game.arrangement()

            val mine = viewFor(game.state, alice)
            val theirs = viewFor(game.state, bob)
            // ADR-007: the deciding seat sees the identities *and* their characteristics, because a scry is
            // decided on them; the opponent sees a count, a seat, and a source zone — and nothing else.
            mine.cards.keys.map { it.name } shouldContainExactly
                listOf("Grizzly Bears", "Island", "Lightning Bolt", "Preordain")
            theirs.cards.keys.map { it.name } shouldNotContain "Grizzly Bears"
            theirs.cards.keys.map { it.name } shouldNotContain "Lightning Bolt"
            theirs.pendingLibraryLook shouldBe
                PendingLibraryLookView(alice, LibraryLookSource.TOP_OF_LIBRARY, 2, awaitingShuffle = false)
            theirs.pendingDecision shouldBe
                DecisionView.Elsewhere(alice, DecisionRequestKind.CHOOSE_LIBRARY_ARRANGEMENT)
        }

        "ADR-006: Ponder reorders the top three, then its optional shuffle draws from the match PRNG" {
            fun ponderGame() =
                lookGame(
                    alice =
                        LookBoard(
                            hand = listOf(obj(10, "Ponder")),
                            battlefield = listOf(obj(0, "Island")),
                            library =
                                listOf(
                                    obj(20, "Grizzly Bears"),
                                    obj(21, "Lightning Bolt"),
                                    obj(22, "Rancor"),
                                    obj(23, "Hill Giant"),
                                ),
                        ),
                )

            val declined = ponderGame()
            declined.castOption("Ponder").payFirstPlan()
            declined.arrangement().options.size shouldBe 6
            // Reverse the top three, decline the shuffle: the chosen order stands and the draw takes Rancor.
            declined.chooseArrangement(hand = emptyList(), top = listOf(2, 1, 0), bottom = emptyList())
            declined.answerYesNo(accept = false)
            declined.driveUntil {
                declined.state.sharedZones.stack
                    .isEmpty()
            }
            declined.handNames(alice) shouldContainExactly listOf("Rancor")
            declined.libraryNames(alice) shouldContainExactly listOf("Lightning Bolt", "Grizzly Bears", "Hill Giant")

            val shuffled = ponderGame()
            shuffled.castOption("Ponder").payFirstPlan()
            shuffled.chooseArrangement(hand = emptyList(), top = listOf(2, 1, 0), bottom = emptyList())
            shuffled.answerYesNo(accept = true)
            shuffled.driveUntil {
                shuffled.state.sharedZones.stack
                    .isEmpty()
            }
            // Accepting consumes seeded entropy, so the PRNG state advances where declining leaves it alone;
            // both games hold four cards across hand and library, only their order differs.
            shuffled.state.rng shouldNotBe declined.state.rng
            (shuffled.handNames(alice) + shuffled.libraryNames(alice)).sorted() shouldBe
                (declined.handNames(alice) + declined.libraryNames(alice)).sorted()
        }

        "ADR-005: Impulse's keep is mandatory — no arrangement leaves the hand empty" {
            val game =
                lookGame(
                    alice =
                        LookBoard(
                            hand = listOf(obj(10, "Impulse")),
                            battlefield = listOf(obj(0, "Island"), obj(1, "Island")),
                            library =
                                listOf(
                                    obj(20, "Grizzly Bears"),
                                    obj(21, "Lightning Bolt"),
                                    obj(22, "Rancor"),
                                    obj(23, "Hill Giant"),
                                    obj(24, "Mountain"),
                                ),
                        ),
                )
            game.castOption("Impulse").payFirstPlan()
            val arrange = game.arrangement()
            arrange.pool.map { it.card.name } shouldContainExactly
                listOf("Grizzly Bears", "Lightning Bolt", "Rancor", "Hill Giant")
            // 4! outcomes, every one of which keeps exactly one card: the "put one of them into your hand"
            // is not a "you may", so the decline the CR 701.16 reveal path would offer has no index here.
            arrange.options.size shouldBe 24
            arrange.options.none { it.toHand.isEmpty() } shouldBe true

            // Keep the Bolt; bottom the rest in the chosen order (the first placed ends up highest).
            game.chooseArrangement(hand = listOf(1), top = emptyList(), bottom = listOf(0, 2, 3))
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            game.handNames(alice) shouldContainExactly listOf("Lightning Bolt")
            game.libraryNames(alice) shouldContainExactly
                listOf("Mountain", "Grizzly Bears", "Rancor", "Hill Giant")
        }

        "CR 400.7: Brainstorm draws three, then puts two hand cards back on top in the chosen order" {
            val game =
                lookGame(
                    alice =
                        LookBoard(
                            hand = listOf(obj(10, "Brainstorm")),
                            battlefield = listOf(obj(0, "Island")),
                            library =
                                listOf(
                                    obj(20, "Grizzly Bears"),
                                    obj(21, "Lightning Bolt"),
                                    obj(22, "Rancor"),
                                    obj(23, "Hill Giant"),
                                ),
                        ),
                )
            game.castOption("Brainstorm").payFirstPlan()
            val arrange = game.arrangement()
            // The draw is the ordinary resolution and runs first, so the pool is the post-draw hand.
            arrange.pool.map { it.card.name } shouldContainExactly
                listOf("Grizzly Bears", "Lightning Bolt", "Rancor")
            // P(3, 2) = 6 ordered placements — "in any order" is a real decision, not a convention.
            arrange.options.size shouldBe 6
            val handIdsBefore =
                game.state.players
                    .getValue(alice)
                    .hand
                    .map { it.id }

            // Put Rancor on top with the Bear beneath it; the Bolt stays in hand.
            game.chooseArrangement(hand = listOf(1), top = listOf(2, 0), bottom = emptyList())
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            game.handNames(alice) shouldContainExactly listOf("Lightning Bolt")
            game.libraryNames(alice) shouldContainExactly listOf("Rancor", "Grizzly Bears", "Hill Giant")
            // CR 400.7: hand -> library is a zone change, so both placed cards are new objects.
            game.state.players
                .getValue(alice)
                .library
                .take(2)
                .none { it.id in handIdsBefore } shouldBe true
            game.state.events
                .filterIsInstance<GameEvent.CardPutOnLibrary>()
                .map { it.card.name } shouldContainExactly listOf("Grizzly Bears", "Rancor")
        }
    })

// ---- driving helpers over ScriptedGame (invariant-checked every transition) -----------------------

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

/** Answers the pending payment request with its first enumerated plan (CR 601.2g). */
private fun ScriptedGame.payFirstPlan(): ScriptedGame {
    val payment = pendingRequest.shouldBeInstanceOf<DecisionRequest.ChoosePaymentPlan>()
    return apply(Decision.SingleSelect(payment.id, 0))
}

/**
 * The pending arrangement request (CR 701.17a), driving priority passes until the spell resolves into it —
 * both seats pass before a spell on the stack resolves (CR 117.4).
 */
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

/** Answers the pending yes/no (CR 601.3b) — Ponder's "You may shuffle." */
private fun ScriptedGame.answerYesNo(accept: Boolean): ScriptedGame {
    val request = pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseYesNo>()
    val index = if (accept) DecisionRequest.ChooseYesNo.ACCEPT else DecisionRequest.ChooseYesNo.DECLINE
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
    while (!predicate() && !isOver && steps < MAX_LOOK_DRIVE_STEPS) {
        passOrOrder()
        steps++
    }
    check(predicate()) { "the drive predicate was not satisfied within $MAX_LOOK_DRIVE_STEPS steps" }
    return this
}

private const val MAX_LOOK_DRIVE_STEPS: Int = 200

// ---- state construction ---------------------------------------------------------------------------

/** The turn these scenarios resume on — late enough that nothing is summoning sick by construction. */
private const val LOOK_TURN: Int = 3

/** One seat's hand, battlefield, and library objects, for constructing a scenario board. */
private data class LookBoard(
    val hand: List<GameObject> = emptyList(),
    val battlefield: List<GameObject> = emptyList(),
    val library: List<GameObject> = emptyList(),
)

/** A hand/battlefield/library object [id] of card [name] (owner reassigned per seat by [lookGame]). */
private fun obj(
    id: Long,
    name: String,
): GameObject = GameObject(ObjectId(id), CardRef(name), alice)

/**
 * A [ScriptedGame] resumed from a handcrafted precombat-main state (ADR-004): alice holds priority on the
 * given boards over the real [MvpCards] definitions, on turn [LOOK_TURN], which is hers. Bob keeps a
 * one-card library so no draw-from-empty loss (CR 704.5c) ends a game mid-scenario.
 */
private fun lookGame(alice: LookBoard): ScriptedGame {
    val aliceSeat = dev.mtgplay.acceptance.alice
    val bobSeat = dev.mtgplay.acceptance.bob
    val bobLibrary = listOf(obj(90, "Mountain"), obj(91, "Mountain")).map { it.copy(owner = bobSeat) }
    val allObjects = alice.hand + alice.battlefield + alice.library + bobLibrary
    val nextId = (allObjects.maxOfOrNull { it.id.value } ?: -1L) + 1
    val state =
        GameState(
            players =
                persistentMapOf(
                    aliceSeat to
                        PlayerState(
                            life = STARTING_LIFE,
                            library = alice.library.toPersistentList(),
                            hand = alice.hand.toPersistentList(),
                            graveyard = persistentListOf(),
                            priorityStatus = PriorityStatus.HOLDS_PRIORITY,
                        ),
                    bobSeat to
                        PlayerState(
                            life = STARTING_LIFE,
                            library = bobLibrary.toPersistentList(),
                            hand = persistentListOf(),
                            graveyard = persistentListOf(),
                        ),
                ),
            turn = Turn(aliceSeat, LOOK_TURN, TurnPhase.PRECOMBAT_MAIN, null),
            sharedZones =
                SharedZones(
                    battlefield = alice.battlefield.toPersistentList(),
                    stack = persistentListOf(),
                    exile = persistentListOf(),
                ),
            nextObjectId = nextId,
            rng = Rng(7),
            events = persistentListOf(),
            definitions = MvpCards.definitions.toPersistentMap(),
        )
    return ScriptedGame.startFrom(state)
}
