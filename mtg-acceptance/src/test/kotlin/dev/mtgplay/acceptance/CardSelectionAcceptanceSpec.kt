package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.acceptance.replay.fingerprint
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.OptionalCostMode
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
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
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
 * The card-selection and draw family, each driven end-to-end through the real engine by [ScriptedGame]
 * (which invariant-checks every transition): Thought Scour's targeted mill and its player-only target
 * enumeration (CR 115.1a, CR 701.13), Mental Note's self-mill, Lórien Revealed's draw-three and its
 * islandcycling search (CR 702.28), Unfathomable Truths' draw-three-plus-Eldrazi-Spawn, and Pursue the
 * Past's lifegain, single-mode loot, and flashback (CR 702.34). Every state is a valid engine input by
 * construction (ADR-004), and every card is cast and resolved for real — nothing is asserted off a
 * definition here.
 */
class CardSelectionAcceptanceSpec :
    StringSpec({

        "CR 701.13a: Thought Scour mills the targeted player's top two and its caster draws one" {
            val game =
                selectionGame(
                    alice =
                        SelectionBoard(
                            hand = listOf(obj(10, "Thought Scour")),
                            battlefield = listOf(obj(0, "Island")),
                            library = listOf(obj(20, "Lightning Bolt"), obj(21, "Island")),
                        ),
                    bob =
                        SelectionBoard(
                            library =
                                listOf(obj(30, "Grizzly Bears"), obj(31, "Rancor"), obj(32, "Mountain")),
                        ),
                )
            game.castTargeting("Thought Scour", Target.Player(bob))
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            // Bob's top two went to bob's graveyard, in mill order; his third card stayed on top.
            game.state.players
                .getValue(bob)
                .graveyard
                .map { it.card } shouldContainExactly listOf(CardRef("Grizzly Bears"), CardRef("Rancor"))
            game.state.players
                .getValue(bob)
                .library
                .map { it.card } shouldContainExactly listOf(CardRef("Mountain"))
            // Alice drew — the "draw a card" clause is hers, not the targeted player's.
            game.state.players
                .getValue(alice)
                .hand
                .map { it.card } shouldContainExactly listOf(CardRef("Lightning Bolt"))
            game.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContainExactly listOf(CardRef("Thought Scour"))
            // CR 701.13a vs CR 701.8a: a mill is narrated as a mill, never as a discard.
            game.state.events
                .filterIsInstance<GameEvent.CardMilled>()
                .map { it.player } shouldContainExactly listOf(bob, bob)
            game.state.events
                .filterIsInstance<GameEvent.CardDiscarded>()
                .shouldBeEmpty()
        }

        "CR 115.1a: Thought Scour's target enumeration offers both players and no creature" {
            val game =
                selectionGame(
                    alice =
                        SelectionBoard(
                            hand = listOf(obj(10, "Thought Scour")),
                            battlefield = listOf(obj(0, "Island"), notSick(obj(1, "Grizzly Bears"))),
                            library = listOf(obj(20, "Island")),
                        ),
                    bob = SelectionBoard(battlefield = listOf(notSick(obj(40, "Hill Giant")))),
                )
            game.castOption("Thought Scour")
            val targets = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
            targets.options shouldContainExactly listOf(Target.Player(alice), Target.Player(bob))
            // Both creatures are on the battlefield and neither is a legal choice — unlike Lightning
            // Bolt's "any target" (CR 115.4), which would enumerate them.
            targets.options shouldNotContain Target.Permanent(ObjectId(1))
            targets.options shouldNotContain Target.Permanent(ObjectId(40))
        }

        "CR 701.13a: Mental Note mills its own controller, then draws — the untargeted twin" {
            val game =
                selectionGame(
                    alice =
                        SelectionBoard(
                            hand = listOf(obj(10, "Mental Note")),
                            battlefield = listOf(obj(0, "Island")),
                            library =
                                listOf(
                                    obj(20, "Grizzly Bears"),
                                    obj(21, "Rancor"),
                                    obj(22, "Lightning Bolt"),
                                    obj(23, "Island"),
                                ),
                        ),
                    bob = SelectionBoard(library = listOf(obj(30, "Mountain"))),
                )
            game.castOption("Mental Note")
            game.payFirstPlan()
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            // The order is printed order: mill two, *then* draw — so the drawn card is the third one down.
            game.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContainExactly
                listOf(CardRef("Grizzly Bears"), CardRef("Rancor"), CardRef("Mental Note"))
            game.state.players
                .getValue(alice)
                .hand
                .map { it.card } shouldContainExactly listOf(CardRef("Lightning Bolt"))
            game.state.players
                .getValue(bob)
                .graveyard
                .shouldBeEmpty()
        }

        "CR 120.1: Lórien Revealed draws three for {3}{U}{U}" {
            val game =
                selectionGame(
                    alice =
                        SelectionBoard(
                            hand = listOf(obj(10, "Lórien Revealed")),
                            battlefield = (0L..4L).map { obj(it, "Island") },
                            library =
                                listOf(
                                    obj(20, "Rancor"),
                                    obj(21, "Lightning Bolt"),
                                    obj(22, "Grizzly Bears"),
                                    obj(23, "Island"),
                                ),
                        ),
                    bob = SelectionBoard(library = listOf(obj(30, "Mountain"))),
                )
            game.castOption("Lórien Revealed")
            game.payFirstPlan()
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            game.state.players
                .getValue(alice)
                .hand
                .map { it.card } shouldContainExactly
                listOf(CardRef("Rancor"), CardRef("Lightning Bolt"), CardRef("Grizzly Bears"))
            game.state.players
                .getValue(alice)
                .library
                .map { it.card } shouldContainExactly listOf(CardRef("Island"))
        }

        "CR 702.28b: Lórien Revealed islandcycles — only Island cards are findable, and the library shuffles" {
            // Islandcycling names the *land subtype*: the Mountain and the Lightning Bolt are not
            // findable, though Ash Barrens' basic landcycling would have offered the Mountain.
            fun playCycle(): ScriptedGame {
                val game =
                    selectionGame(
                        alice =
                            SelectionBoard(
                                hand = listOf(obj(10, "Lórien Revealed")),
                                battlefield = listOf(obj(0, "Island")),
                                library =
                                    listOf(
                                        obj(20, "Mountain"),
                                        obj(21, "Island"),
                                        obj(22, "Lightning Bolt"),
                                        obj(23, "Island"),
                                    ),
                            ),
                        bob = SelectionBoard(library = listOf(obj(30, "Mountain"))),
                    )
                game.activateAbility("Lórien Revealed")
                game.payFirstPlan()
                game.driveUntil { game.pendingRequest is DecisionRequest.ChooseFromLibrary }
                val find = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseFromLibrary>()
                find.options.map { it.card } shouldContainExactly listOf(CardRef("Island"), CardRef("Island"))
                game.apply(Decision.SingleSelect(find.id, 0))
                return game.driveUntil {
                    game.state.sharedZones.stack
                        .isEmpty()
                }
            }
            val game = playCycle()
            // The found Island is in hand, Lórien Revealed paid the cost by discarding itself, and the
            // library lost the found card.
            game.state.players
                .getValue(alice)
                .hand
                .map { it.card } shouldContainExactly listOf(CardRef("Island"))
            game.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContain CardRef("Lórien Revealed")
            game.state.players
                .getValue(alice)
                .library
                .size shouldBe 3
            // The search shuffled through the seeded match PRNG (ADR-006), so the Rng state advanced…
            game.state.rng.state shouldNotBe Rng(0).state
            // …and the identical scripted sequence reproduces the identical final state, shuffle included.
            fingerprint(playCycle().state) shouldBe fingerprint(game.state)
        }

        "CR 707.2: Unfathomable Truths draws three and creates one Eldrazi Spawn token" {
            val game =
                selectionGame(
                    alice =
                        SelectionBoard(
                            hand = listOf(obj(10, "Unfathomable Truths")),
                            battlefield = (0L..4L).map { obj(it, "Island") },
                            library =
                                listOf(
                                    obj(20, "Rancor"),
                                    obj(21, "Lightning Bolt"),
                                    obj(22, "Grizzly Bears"),
                                    obj(23, "Island"),
                                ),
                        ),
                    bob = SelectionBoard(library = listOf(obj(30, "Mountain"))),
                )
            game.castOption("Unfathomable Truths")
            game.payFirstPlan()
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            game.state.players
                .getValue(alice)
                .hand
                .map { it.card } shouldContainExactly
                listOf(CardRef("Rancor"), CardRef("Lightning Bolt"), CardRef("Grizzly Bears"))
            // The token is the same Eldrazi Spawn Malevolent Rumble makes; it is on the battlefield
            // under the caster's control, and it is not a library or graveyard card.
            game.state.sharedZones.battlefield
                .count { it.card == CardRef.token("Eldrazi Spawn") } shouldBe 1
            game.state.sharedZones.battlefield
                .single { it.card == CardRef.token("Eldrazi Spawn") }
                .owner shouldBe alice
        }

        "CR 601.3b: Pursue the Past gains 2, then offers the discard mode only, then draws two" {
            val game =
                selectionGame(
                    alice =
                        SelectionBoard(
                            hand = listOf(obj(10, "Pursue the Past"), obj(11, "Grizzly Bears")),
                            // A land is on the battlefield, so a sacrifice-a-land mode would be
                            // performable if the card printed one. It does not.
                            battlefield = listOf(obj(0, "Mountain"), obj(1, "Plains")),
                            library = listOf(obj(20, "Rancor"), obj(21, "Lightning Bolt"), obj(22, "Island")),
                        ),
                    bob = SelectionBoard(library = listOf(obj(30, "Mountain"))),
                )
            game.castOption("Pursue the Past")
            game.payFirstPlan()
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseCostMode }
            // The lifegain clause resolved before the loot clause — printed order (CR 608.2c).
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE + PURSUE_LIFEGAIN
            val modes = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseCostMode>()
            modes.options shouldContainExactly listOf(OptionalCostMode.DiscardCard)
            game.apply(Decision.SingleSelect(modes.id, 0))
            val discard = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseOptionalCostObject>()
            game.apply(Decision.MultiSelect(discard.id, listOf(0)))
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            game.state.players
                .getValue(alice)
                .hand
                .map { it.card } shouldContainExactly listOf(CardRef("Rancor"), CardRef("Lightning Bolt"))
            game.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContainExactly
                listOf(CardRef("Grizzly Bears"), CardRef("Pursue the Past"))
        }

        "CR 601.3b: declining Pursue the Past's optional discard still gains the 2 life and draws nothing" {
            val game =
                selectionGame(
                    alice =
                        SelectionBoard(
                            hand = listOf(obj(10, "Pursue the Past"), obj(11, "Grizzly Bears")),
                            battlefield = listOf(obj(0, "Mountain"), obj(1, "Plains")),
                            library = listOf(obj(20, "Rancor"), obj(21, "Lightning Bolt")),
                        ),
                    bob = SelectionBoard(library = listOf(obj(30, "Mountain"))),
                )
            game.castOption("Pursue the Past")
            game.payFirstPlan()
            game.driveUntil { game.pendingRequest is DecisionRequest.ChooseCostMode }
            val modes = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseCostMode>()
            game.apply(Decision.SingleSelect(modes.id, modes.declineIndex))
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE + PURSUE_LIFEGAIN
            // Nothing was discarded and nothing drawn: the hand still holds the one spare card.
            game.state.players
                .getValue(alice)
                .hand
                .map { it.card } shouldContainExactly listOf(CardRef("Grizzly Bears"))
            game.state.players
                .getValue(alice)
                .library
                .size shouldBe 2
        }

        "CR 702.34e: Pursue the Past flashed back from the graveyard is exiled as it leaves the stack" {
            val game =
                selectionGame(
                    alice =
                        SelectionBoard(
                            battlefield =
                                listOf(obj(0, "Mountain"), obj(1, "Mountain"), obj(2, "Mountain"), obj(3, "Plains")),
                            graveyard = listOf(obj(10, "Pursue the Past")),
                            library = listOf(obj(20, "Rancor"), obj(21, "Lightning Bolt")),
                        ),
                    bob = SelectionBoard(library = listOf(obj(30, "Mountain"))),
                )
            game.castFlashback("Pursue the Past")
            game.payFirstPlan()
            // The hand is empty, so the only printed mode is unperformable: CR 601.3b's "may" cannot be
            // taken, no mode choice is surfaced at all, and the spell finishes with the lifegain only.
            game.driveUntil {
                game.state.sharedZones.stack
                    .isEmpty()
            }
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE + PURSUE_LIFEGAIN
            game.state.players
                .getValue(alice)
                .library
                .size shouldBe 2
            game.state.sharedZones.exile
                .count { it.card == CardRef("Pursue the Past") } shouldBe 1
            game.state.players
                .getValue(alice)
                .graveyard
                .none { it.card == CardRef("Pursue the Past") }
                .shouldBeTrue()
        }
    })

/** The life Pursue the Past's controller gains on resolution (CR 119.3). */
private const val PURSUE_LIFEGAIN: Int = 2

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

/** Selects the graveyard (flashback) cast option for [name] (CR 702.34a). */
private fun ScriptedGame.castFlashback(name: String): ScriptedGame {
    val window = action()
    val index =
        window.options.indexOfFirst {
            it is PriorityOption.CastSpell && it.card == CardRef(name) && it.source == CastSource.GRAVEYARD
        }
    check(index >= 0) { "no flashback cast for $name in ${window.options}" }
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
    while (!predicate() && !isOver && steps < MAX_SELECTION_DRIVE_STEPS) {
        passOrOrder()
        steps++
    }
    check(predicate()) { "the drive predicate was not satisfied within $MAX_SELECTION_DRIVE_STEPS steps" }
    return this
}

private const val MAX_SELECTION_DRIVE_STEPS: Int = 200

// ---- state construction ---------------------------------------------------------------------------

/** The turn these scenarios resume on — late enough that nothing is summoning sick by construction. */
private const val SELECTION_TURN: Int = 3

/** One seat's hand, battlefield, library, and graveyard objects, for constructing a scenario board. */
private data class SelectionBoard(
    val hand: List<GameObject> = emptyList(),
    val battlefield: List<GameObject> = emptyList(),
    val library: List<GameObject> = emptyList(),
    val graveyard: List<GameObject> = emptyList(),
)

/** A hand/battlefield/library object [id] of card [name] (owner reassigned per seat by [selectionGame]). */
private fun obj(
    id: Long,
    name: String,
): GameObject = GameObject(ObjectId(id), CardRef(name), alice)

/** Marks a battlefield creature as no longer summoning sick (controlled since the turn began). */
private fun notSick(obj: GameObject): GameObject = obj.copy(summoningSick = false)

/**
 * A [ScriptedGame] resumed from a handcrafted precombat-main state (ADR-004): [holder] holds priority on
 * the given [alice] and [bob] boards over the real [MvpCards] definitions; the turn is [SELECTION_TURN]
 * and belongs to alice. Unlike the other suites' builders both seats get a real library, because the
 * mill cantrips read the *opponent's*. Every transition is invariant-checked by the driver.
 */
private fun selectionGame(
    alice: SelectionBoard = SelectionBoard(),
    bob: SelectionBoard = SelectionBoard(),
    holder: PlayerId = dev.mtgplay.acceptance.alice,
): ScriptedGame {
    val aliceSeat = dev.mtgplay.acceptance.alice
    val bobSeat = dev.mtgplay.acceptance.bob
    val bobHand = bob.hand.map { it.copy(owner = bobSeat) }
    val bobField = bob.battlefield.map { it.copy(owner = bobSeat) }
    val bobLibrary = bob.library.map { it.copy(owner = bobSeat) }
    val bobGrave = bob.graveyard.map { it.copy(owner = bobSeat) }
    val allObjects =
        alice.hand + alice.battlefield + alice.library + alice.graveyard +
            bobHand + bobField + bobLibrary + bobGrave
    val nextId = (allObjects.maxOfOrNull { it.id.value } ?: -1L) + 1

    fun priorityOf(seat: PlayerId) = if (seat == holder) PriorityStatus.HOLDS_PRIORITY else PriorityStatus.NONE
    val state =
        GameState(
            players =
                persistentMapOf(
                    aliceSeat to
                        PlayerState(
                            life = STARTING_LIFE,
                            library = alice.library.toPersistentList(),
                            hand = alice.hand.toPersistentList(),
                            graveyard = alice.graveyard.toPersistentList(),
                            priorityStatus = priorityOf(aliceSeat),
                        ),
                    bobSeat to
                        PlayerState(
                            life = STARTING_LIFE,
                            library = bobLibrary.toPersistentList(),
                            hand = bobHand.toPersistentList(),
                            graveyard = bobGrave.toPersistentList(),
                            priorityStatus = priorityOf(bobSeat),
                        ),
                ),
            turn = Turn(aliceSeat, SELECTION_TURN, TurnPhase.PRECOMBAT_MAIN, null),
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
