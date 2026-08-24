package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.cards.LAST_BREATH_LIFEGAIN
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.cards.SMASH_TO_SMITHEREENS_DAMAGE
import dev.mtgplay.core.definition.CastSource
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
 * The removal family, each card cast and resolved for real through the engine by [ScriptedGame]
 * (which invariant-checks every transition): the CR 701.7a destroy, the CR 701.3a exile, the
 * CR 702.12b indestructible exemption a Bridge finally makes reachable, "that artifact's controller"
 * as CR 608.2h last-known information, Last Breath's CR 613 layered power restriction, Ancient
 * Grudge's flashback (CR 702.34e), and the CR 608.2b fizzle a removal spell is the first spell in the
 * pool to be able to suffer against a *permanent* target.
 *
 * Nothing here is asserted off a definition; every state is a valid engine input by construction
 * (ADR-004), and every assertion reads a zone, a life total, or the event log after a real cast.
 */
class RemovalAcceptanceSpec :
    StringSpec({

        "CR 701.7a: Terminate destroys the targeted creature, which goes to its owner's graveyard" {
            val game =
                removalGame(
                    alice =
                        RemovalBoard(
                            hand = listOf(obj(10, "Terminate")),
                            battlefield = listOf(obj(0, "Swamp"), obj(1, "Mountain")),
                        ),
                    bob = RemovalBoard(battlefield = listOf(notSick(obj(40, "Grizzly Bears")))),
                )

            game.castTargeting("Terminate", Target.Permanent(ObjectId(40)))
            game.driveUntilStackEmpty()

            game.state.sharedZones.battlefield
                .map { it.card } shouldContainExactly listOf(CardRef("Swamp"), CardRef("Mountain"))
            // CR 701.7a: to its *owner's* graveyard — bob's, not the caster's.
            game.state.players
                .getValue(bob)
                .graveyard
                .map { it.card } shouldContainExactly listOf(CardRef("Grizzly Bears"))
            game.state.events
                .filterIsInstance<GameEvent.PermanentDestroyed>()
                .single()
                .card shouldBe CardRef("Grizzly Bears")
        }

        "CR 115.1b: Cast Down's target enumeration offers every creature and no player" {
            val game =
                removalGame(
                    alice =
                        RemovalBoard(
                            hand = listOf(obj(10, "Cast Down")),
                            battlefield = listOf(obj(0, "Swamp"), obj(1, "Swamp"), notSick(obj(2, "Hill Giant"))),
                        ),
                    bob = RemovalBoard(battlefield = listOf(notSick(obj(40, "Grizzly Bears")), obj(41, "Mountain"))),
                )

            game.castOption("Cast Down")

            val targets = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
            targets.options shouldContainExactly
                listOf(Target.Permanent(ObjectId(2)), Target.Permanent(ObjectId(40)))
            // Unlike Lightning Bolt's "any target" (CR 115.4), no player is a legal choice, and
            // unlike "target permanent" the Mountain is not one either.
            targets.options shouldNotContain Target.Player(alice)
            targets.options shouldNotContain Target.Player(bob)
            targets.options shouldNotContain Target.Permanent(ObjectId(41))
        }

        "CR 601.2c: Terminate is not castable at all with no creature on the battlefield" {
            val game =
                removalGame(
                    alice =
                        RemovalBoard(
                            hand = listOf(obj(10, "Terminate"), obj(11, "Lightning Bolt")),
                            battlefield = listOf(obj(0, "Swamp"), obj(1, "Mountain")),
                        ),
                )

            val window = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseAction>()
            val castable =
                window.options.filterIsInstance<PriorityOption.CastSpell>().map { it.card }
            // A spell whose only target has no legal choice is excluded from enumeration (ADR-005);
            // Lightning Bolt, whose "any target" always finds a player, stays castable.
            castable shouldContainExactly listOf(CardRef("Lightning Bolt"))
        }

        "CR 702.12b: Smash to Smithereens destroys nothing on an indestructible Bridge, and still deals 3" {
            val game =
                removalGame(
                    alice =
                        RemovalBoard(
                            hand = listOf(obj(10, "Smash to Smithereens")),
                            battlefield = listOf(obj(0, "Mountain"), obj(1, "Mountain")),
                        ),
                    bob = RemovalBoard(battlefield = listOf(obj(40, "Mistvault Bridge"))),
                )

            game.castTargeting("Smash to Smithereens", Target.Permanent(ObjectId(40)))
            game.driveUntilStackEmpty()

            // CR 702.12b: the Bridge is a legal target and survives the destruction outright.
            game.state.sharedZones.battlefield
                .map { it.id } shouldContainExactly listOf(ObjectId(0), ObjectId(1), ObjectId(40))
            game.state.events
                .filterIsInstance<GameEvent.PermanentDestroyed>()
                .shouldBeEmpty()
            // The damage clause is independent of whether the destruction succeeded (CR 608.2h).
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - SMASH_TO_SMITHEREENS_DAMAGE
        }

        "CR 608.2h: Smash to Smithereens damages the destroyed artifact's controller as last-known information" {
            val game =
                removalGame(
                    alice =
                        RemovalBoard(
                            hand = listOf(obj(10, "Smash to Smithereens")),
                            battlefield = listOf(obj(0, "Mountain"), obj(1, "Mountain")),
                        ),
                    bob = RemovalBoard(battlefield = listOf(obj(40, "Great Furnace"))),
                )

            game.castTargeting("Smash to Smithereens", Target.Permanent(ObjectId(40)))
            game.driveUntilStackEmpty()

            // The artifact land is gone, and the controller read *before* it left took the damage —
            // reading it afterwards would have found nothing to damage.
            game.state.players
                .getValue(bob)
                .graveyard
                .map { it.card } shouldContainExactly listOf(CardRef("Great Furnace"))
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE - SMASH_TO_SMITHEREENS_DAMAGE
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE
        }

        "CR 702.34e: Ancient Grudge flashed back from the graveyard destroys an artifact and exiles itself" {
            val game =
                removalGame(
                    alice =
                        RemovalBoard(
                            battlefield = listOf(obj(0, "Forest")),
                            graveyard = listOf(obj(10, "Ancient Grudge")),
                        ),
                    bob = RemovalBoard(battlefield = listOf(obj(40, "Vault of Whispers"))),
                )

            game.castFlashbackTargeting("Ancient Grudge", Target.Permanent(ObjectId(40)))
            game.driveUntilStackEmpty()

            game.state.players
                .getValue(bob)
                .graveyard
                .map { it.card } shouldContainExactly listOf(CardRef("Vault of Whispers"))
            // CR 702.34e: the flashed-back card is exiled instead of returning to the graveyard.
            game.state.players
                .getValue(alice)
                .graveyard
                .shouldBeEmpty()
            game.state.sharedZones.exile
                .map { it.card } shouldContainExactly listOf(CardRef("Ancient Grudge"))
        }

        "CR 701.3a: Scour from Existence exiles an indestructible permanent no destroy could answer" {
            val lands = (0..6).map { obj(it.toLong(), "Forest") }
            val game =
                removalGame(
                    alice = RemovalBoard(hand = listOf(obj(10, "Scour from Existence")), battlefield = lands),
                    bob = RemovalBoard(battlefield = listOf(obj(40, "Slagwoods Bridge"))),
                )

            game.castTargeting("Scour from Existence", Target.Permanent(ObjectId(40)))
            game.driveUntilStackEmpty()

            // Exiling is not destroying, so CR 702.12b never applies: the Bridge is gone.
            game.state.sharedZones.battlefield
                .none { it.card == CardRef("Slagwoods Bridge") } shouldBe true
            game.state.players
                .getValue(bob)
                .graveyard
                .shouldBeEmpty()
            game.state.sharedZones.exile
                .map { it.card } shouldContainExactly listOf(CardRef("Slagwoods Bridge"))
            game.state.events
                .filterIsInstance<GameEvent.PermanentExiled>()
                .single()
                .card shouldBe CardRef("Slagwoods Bridge")
        }

        "CR 119.3: Last Breath exiles a power-2 creature and *its controller* gains 4 life" {
            val game =
                removalGame(
                    alice =
                        RemovalBoard(
                            hand = listOf(obj(10, "Last Breath")),
                            battlefield = listOf(obj(0, "Plains"), obj(1, "Plains")),
                        ),
                    bob = RemovalBoard(battlefield = listOf(notSick(obj(40, "Grizzly Bears")))),
                )

            game.castTargeting("Last Breath", Target.Permanent(ObjectId(40)))
            game.driveUntilStackEmpty()

            game.state.sharedZones.exile
                .map { it.card } shouldContainExactly listOf(CardRef("Grizzly Bears"))
            // The lifegain is the *target's* controller's, which is the drawback the card prints.
            game.state.players
                .getValue(bob)
                .life shouldBe STARTING_LIFE + LAST_BREATH_LIFEGAIN
            game.state.players
                .getValue(alice)
                .life shouldBe STARTING_LIFE
        }

        "CR 613: Last Breath reads layered power — a Rancor'd 2/2 is not a legal target, a bare one is" {
            val game =
                removalGame(
                    alice =
                        RemovalBoard(
                            hand = listOf(obj(10, "Last Breath")),
                            battlefield = listOf(obj(0, "Plains"), obj(1, "Plains")),
                        ),
                    bob =
                        RemovalBoard(
                            battlefield =
                                listOf(
                                    notSick(obj(40, "Grizzly Bears")),
                                    notSick(obj(41, "Grizzly Bears")),
                                    notSick(obj(42, "Hill Giant")),
                                    obj(43, "Rancor").copy(attachedTo = ObjectId(41)),
                                ),
                        ),
                )

            game.castOption("Last Breath")

            // Object 41 is a 2/2 with Rancor's +2/+0 — power 4 in-game (CR 613 sublayer 7c), so it is
            // not offered even though its *printed* power is 2. The 3/3 Hill Giant is not offered
            // either, and the bare 2/2 is.
            val targets = game.pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
            targets.options shouldContainExactly listOf(Target.Permanent(ObjectId(40)))
        }

        "CR 608.2b: Terminate does not resolve when its only target dies in response" {
            val game =
                removalGame(
                    alice =
                        RemovalBoard(
                            hand = listOf(obj(10, "Terminate")),
                            battlefield = listOf(obj(0, "Swamp"), obj(1, "Mountain")),
                        ),
                    bob =
                        RemovalBoard(
                            hand = listOf(obj(50, "Lightning Bolt")),
                            battlefield = listOf(obj(40, "Mountain"), notSick(obj(41, "Grizzly Bears"))),
                        ),
                )

            game.castTargeting("Terminate", Target.Permanent(ObjectId(41)))
            // CR 117.3c: the caster keeps priority after casting, so alice passes it to bob first.
            game.passOrOrder()
            // Bob answers by Bolting his own creature: it dies to the CR 704.5g state-based action
            // before Terminate resolves, so Terminate's only target is gone.
            game.castTargeting("Lightning Bolt", Target.Permanent(ObjectId(41)))
            game.driveUntilStackEmpty()

            game.state.events
                .filterIsInstance<GameEvent.PermanentDestroyed>()
                .shouldBeEmpty()
            game.state.events
                .filterIsInstance<GameEvent.SpellFizzled>()
                .map { it.card } shouldContainExactly listOf(CardRef("Terminate"))
            // The creature died to damage (CR 704.5g), and both spells are in their graveyards. Bob's
            // graveyard is ordered Bolt-then-Bears: the spell's card leaves the stack on resolution
            // (CR 608.2m), and only the following CR 704.3 check kills the creature.
            game.state.players
                .getValue(bob)
                .graveyard
                .map { it.card } shouldContainExactly
                listOf(CardRef("Lightning Bolt"), CardRef("Grizzly Bears"))
            game.state.players
                .getValue(alice)
                .graveyard
                .map { it.card } shouldContainExactly listOf(CardRef("Terminate"))
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

/** Answers the pending target request with [target] (CR 601.2c). */
private fun ScriptedGame.chooseTarget(
    target: Target,
    name: String,
): ScriptedGame {
    val targets = pendingRequest.shouldBeInstanceOf<DecisionRequest.ChooseTargets>()
    val index = targets.options.indexOf(target)
    check(index >= 0) { "no legal target $target for $name in ${targets.options}" }
    return apply(Decision.SingleSelect(targets.id, index))
}

/** Casts the targeted spell [name] at [target], paying its first plan (CR 601.2c, CR 601.2g). */
private fun ScriptedGame.castTargeting(
    name: String,
    target: Target,
): ScriptedGame {
    castOption(name)
    chooseTarget(target, name)
    return payFirstPlan()
}

/** Casts [name] from the graveyard for its flashback cost (CR 702.34a), targeting [target]. */
private fun ScriptedGame.castFlashbackTargeting(
    name: String,
    target: Target,
): ScriptedGame {
    val window = action()
    val index =
        window.options.indexOfFirst {
            it is PriorityOption.CastSpell && it.card == CardRef(name) && it.source == CastSource.GRAVEYARD
        }
    check(index >= 0) { "no flashback cast for $name in ${window.options}" }
    apply(Decision.SingleSelect(window.id, index))
    chooseTarget(target, name)
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

/** Advances (passing / declining combat / ordering triggers) until the stack is empty (CR 405). */
private fun ScriptedGame.driveUntilStackEmpty(): ScriptedGame {
    var steps = 0
    while (state.sharedZones.stack.isNotEmpty() && !isOver && steps < MAX_REMOVAL_DRIVE_STEPS) {
        passOrOrder()
        steps++
    }
    check(
        state.sharedZones.stack
            .isEmpty(),
    ) { "the stack was not emptied within $MAX_REMOVAL_DRIVE_STEPS steps" }
    return this
}

private const val MAX_REMOVAL_DRIVE_STEPS: Int = 200

// ---- state construction ---------------------------------------------------------------------------

/** The turn these scenarios resume on — late enough that nothing is summoning sick by construction. */
private const val REMOVAL_TURN: Int = 3

/** One seat's hand, battlefield, and graveyard objects, for constructing a scenario board. */
private data class RemovalBoard(
    val hand: List<GameObject> = emptyList(),
    val battlefield: List<GameObject> = emptyList(),
    val graveyard: List<GameObject> = emptyList(),
)

/** A hand/battlefield/graveyard object [id] of card [name] (owner reassigned per seat by [removalGame]). */
private fun obj(
    id: Long,
    name: String,
): GameObject = GameObject(ObjectId(id), CardRef(name), alice)

/** Marks a battlefield creature as no longer summoning sick (controlled since the turn began). */
private fun notSick(obj: GameObject): GameObject = obj.copy(summoningSick = false)

/**
 * A [ScriptedGame] resumed from a handcrafted precombat-main state (ADR-004): alice holds priority on
 * the given [alice] and [bob] boards over the real [MvpCards] definitions; the turn is [REMOVAL_TURN]
 * and belongs to alice. Neither seat needs a library — no removal spell draws — so both are empty; the
 * CR 704.5c loss never fires because nothing in these scenarios draws.
 */
private fun removalGame(
    alice: RemovalBoard = RemovalBoard(),
    bob: RemovalBoard = RemovalBoard(),
): ScriptedGame {
    val aliceSeat = dev.mtgplay.acceptance.alice
    val bobSeat = dev.mtgplay.acceptance.bob
    val bobHand = bob.hand.map { it.copy(owner = bobSeat) }
    val bobField = bob.battlefield.map { it.copy(owner = bobSeat) }
    val bobGrave = bob.graveyard.map { it.copy(owner = bobSeat) }
    val allObjects =
        alice.hand + alice.battlefield + alice.graveyard + bobHand + bobField + bobGrave
    val nextId = (allObjects.maxOfOrNull { it.id.value } ?: -1L) + 1

    fun priorityOf(seat: PlayerId) = if (seat == aliceSeat) PriorityStatus.HOLDS_PRIORITY else PriorityStatus.NONE
    val state =
        GameState(
            players =
                persistentMapOf(
                    aliceSeat to
                        PlayerState(
                            life = STARTING_LIFE,
                            library = persistentListOf(),
                            hand = alice.hand.toPersistentList(),
                            graveyard = alice.graveyard.toPersistentList(),
                            priorityStatus = priorityOf(aliceSeat),
                        ),
                    bobSeat to
                        PlayerState(
                            life = STARTING_LIFE,
                            library = persistentListOf(),
                            hand = bobHand.toPersistentList(),
                            graveyard = bobGrave.toPersistentList(),
                            priorityStatus = priorityOf(bobSeat),
                        ),
                ),
            turn = Turn(aliceSeat, REMOVAL_TURN, TurnPhase.PRECOMBAT_MAIN, null),
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
