package dev.mtgplay.rules

import dev.mtgplay.core.definition.Dungeon
import dev.mtgplay.core.definition.DungeonRoom
import dev.mtgplay.core.definition.DungeonRoomAbility
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.effect.takeTheInitiative
import dev.mtgplay.rules.engine.DamageAssignment
import dev.mtgplay.rules.engine.applyVentureRoomChoice
import dev.mtgplay.rules.engine.enqueueUpkeepVenture
import dev.mtgplay.rules.engine.fireInitiativeHandover
import dev.mtgplay.rules.engine.pendingVentureRequest
import dev.mtgplay.rules.engine.resolveVentureTrigger
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/**
 * The initiative (CR 701.51) and the venture keyword action through a dungeon (CR 309, CR 701.49) —
 * `W10-A`.
 *
 * Driven against a **fixture dungeon** rather than the Undercity: `mtg-rules` names no card (ADR-003),
 * and the fixture is also the only way to exercise CR 309.6 completion, since the real Undercity's last
 * room is one of the two this engine cannot yet run (see `mtg-cards`' `Initiative.kt`). Its graph is the
 * Undercity's shape in miniature — an entrance that branches, two middles that rejoin, and a terminal
 * room — which is what every rule below actually depends on.
 */
class InitiativeSpec :
    StringSpec({
        "CR 701.51a: taking the initiative makes you the holder and fires the venture ability" {
            val taken = takeTheInitiative(baseState(), alice, fixtureDungeon)

            taken.initiative.shouldNotBeNull().holder shouldBe alice
            taken.events
                .last()
                .shouldBeInstanceOf<GameEvent.InitiativeTaken>()
                .previousHolder
                .shouldBeNull()
            // CR 603.3b: the venture is a triggered ability, so it waits to be put on the stack rather
            // than happening inside the effect that granted the initiative.
            val fired = taken.pendingTriggers.single()
            fired.controller shouldBe alice
            fired.ability.condition shouldBe TriggerCondition.VentureIntoDungeon
            fired.ability.zoneScope shouldBe TriggerZoneScope.Command
            // CR 309.4: nothing has moved yet — the marker arrives when the ability resolves.
            taken.initiative
                .shouldNotBeNull()
                .markers.keys
                .shouldBeEmpty()
        }

        "CR 309.4: the first venture enters the dungeon at its first room and triggers that room" {
            val entered = ventureOnce(takeTheInitiative(baseState(), alice, fixtureDungeon), alice)

            entered.initiative.shouldNotBeNull().roomOf(alice) shouldBe 0
            entered.events
                .map { it }
                .filterIsInstance<GameEvent.VenturedIntoDungeon>()
                .single()
                .room shouldBe
                "Entrance"
            // CR 309.5: the room's own ability is a separate triggered ability that uses the stack —
            // so it is respondable, and is already on the stack by the time priority is granted.
            roomAbilityOnStack(entered).trigger.controller shouldBe alice
        }

        "CR 701.51a: taking the initiative you already hold still ventures" {
            val first = ventureOnce(takeTheInitiative(baseState(), alice, fixtureDungeon), alice)
            val cleared = first.copy(pendingTriggers = persistentListOf())
            val again = takeTheInitiative(cleared, alice, fixtureDungeon)

            again.initiative.shouldNotBeNull().holder shouldBe alice
            again.events
                .last()
                .shouldBeInstanceOf<GameEvent.InitiativeTaken>()
                .previousHolder shouldBe alice
            // The whole point: a second take is a second venture, not a no-op.
            again.pendingTriggers
                .single()
                .ability.condition shouldBe TriggerCondition.VentureIntoDungeon
        }

        "CR 309.4: a branching room pauses for the venturing player's enumerated choice" {
            val entered = ventureOnce(takeTheInitiative(baseState(), alice, fixtureDungeon), alice)
            val paused = ventureAgainAtUpkeep(entered, alice)

            val needs = paused.shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val request = needs.request.shouldBeInstanceOf<DecisionRequest.ChooseDungeonRoom>()
            request.seat shouldBe alice
            request.dungeon shouldBe "Fixture Dungeon"
            request.fromRoom shouldBe "Entrance"
            request.options.map { it.name } shouldContainExactly listOf("Left", "Right")
            // CR 309.4: the marker has *not* moved while the branch is pending — the move is one
            // transition, and the state is resumable to exactly this request (ADR-004).
            needs.state.initiative
                .shouldNotBeNull()
                .roomOf(alice) shouldBe 0
            pendingVentureRequest(needs.state) shouldBe request
        }

        "CR 309.4: answering the branch moves the marker to the chosen room and triggers it" {
            val entered = ventureOnce(takeTheInitiative(baseState(), alice, fixtureDungeon), alice)
            val paused = ventureAgainAtUpkeep(entered, alice).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val request = paused.request.shouldBeInstanceOf<DecisionRequest.ChooseDungeonRoom>()

            val moved = stateOf(applyVentureRoomChoice(paused.state, request.options[1].room))

            moved.pendingVenture.shouldBeNull()
            moved.initiative.shouldNotBeNull().roomOf(alice) shouldBe ROOM_RIGHT
            roomAbilityOnStack(moved).trigger.controller shouldBe alice
        }

        "CR 309.6: reaching the last room completes the dungeon, and the next venture starts over" {
            // Walk Entrance -> Right -> End, then venture once more.
            val atEnd = markerOn(takeTheInitiative(baseState(), alice, fixtureDungeon), alice, ROOM_END)
            val restarted = stateOf(ventureAgainAtUpkeep(atEnd, alice))

            restarted.events
                .filterIsInstance<GameEvent.DungeonCompleted>()
                .single()
                .dungeon shouldBe
                "Fixture Dungeon"
            restarted.initiative.shouldNotBeNull().roomOf(alice) shouldBe 0
        }

        "CR 309.4: a player who loses and regains the initiative resumes from their own room" {
            val alicesRun = markerOn(takeTheInitiative(baseState(), alice, fixtureDungeon), alice, ROOM_RIGHT)
            val bobsTurn = takeTheInitiative(alicesRun.copy(pendingTriggers = persistentListOf()), bob, fixtureDungeon)
            val bobEntered = ventureOnce(bobsTurn, bob)

            // Two players, two dungeons: bob starts at the entrance while alice's marker stays put.
            bobEntered.initiative.shouldNotBeNull().roomOf(bob) shouldBe 0
            bobEntered.initiative.shouldNotBeNull().roomOf(alice) shouldBe ROOM_RIGHT

            val back = takeTheInitiative(bobEntered.copy(pendingTriggers = persistentListOf()), alice, fixtureDungeon)
            val resumed = ventureOnce(back, alice)
            // CR 309.4: alice resumes from Right, so her marker lands on End — not back at the entrance.
            resumed.initiative.shouldNotBeNull().roomOf(alice) shouldBe ROOM_END
        }

        "CR 701.51b: the initiative holder's upkeep fires a venture, and nobody else's does" {
            val held = takeTheInitiative(baseState(), alice, fixtureDungeon).copy(pendingTriggers = persistentListOf())

            enqueueUpkeepVenture(held, alice).pendingTriggers shouldHaveSize 1
            enqueueUpkeepVenture(held, bob).pendingTriggers.shouldBeEmpty()
            // CR 701.51b: with no initiative in the game at all, no upkeep ventures.
            enqueueUpkeepVenture(baseState(), alice).pendingTriggers.shouldBeEmpty()
        }

        "CR 701.51c: combat damage to the initiative holder hands the initiative to the attacker" {
            val held = takeTheInitiative(baseState(), alice, fixtureDungeon).copy(pendingTriggers = persistentListOf())
            val handed = fireInitiativeHandover(held, listOf(DamageAssignment(BOB_ATTACKER, Target.Player(alice), 2)))

            handed.initiative.shouldNotBeNull().holder shouldBe bob
            handed.pendingTriggers.single().controller shouldBe bob
        }

        "CR 701.51c: 'one or more creatures a player controls' hands the initiative over exactly once" {
            val held = takeTheInitiative(baseState(), alice, fixtureDungeon).copy(pendingTriggers = persistentListOf())
            val alphaStrike =
                listOf(
                    DamageAssignment(BOB_ATTACKER, Target.Player(alice), 2),
                    DamageAssignment(BOB_SECOND_ATTACKER, Target.Player(alice), 3),
                )
            val handed = fireInitiativeHandover(held, alphaStrike)

            handed.initiative.shouldNotBeNull().holder shouldBe bob
            // Two attackers, one handover — and therefore one venture, not two.
            handed.pendingTriggers shouldHaveSize 1
        }

        "CR 701.51c: damage that reached nobody but a blocker hands nothing over" {
            val held = takeTheInitiative(baseState(), alice, fixtureDungeon).copy(pendingTriggers = persistentListOf())
            val blocked = listOf(DamageAssignment(BOB_ATTACKER, Target.Permanent(ObjectId(77)), 2))

            fireInitiativeHandover(held, blocked).initiative.shouldNotBeNull().holder shouldBe alice
            // CR 120.8: and zero damage is not dealt, so it is not a handover either.
            val nothing = listOf(DamageAssignment(BOB_ATTACKER, Target.Player(alice), 0))
            fireInitiativeHandover(held, nothing).pendingTriggers.shouldBeEmpty()
        }

        "CR 309.5: entering a room this engine cannot run fails loudly rather than doing nothing" {
            val taken = takeTheInitiative(baseState(), alice, brokenDungeon)
            val failure = shouldThrow<IllegalStateException> { ventureOnce(taken, alice) }

            failure.message.shouldNotBeNull().shouldContain("Locked Door")
            failure.message.shouldNotBeNull().shouldContain("a framework this engine does not have")
        }
    })

/** The index of Left in [fixtureDungeon]'s room list. */
private const val ROOM_LEFT: Int = 1

/** The index of Right in [fixtureDungeon]'s room list. */
private const val ROOM_RIGHT: Int = 2

/** The index of End in [fixtureDungeon]'s room list. */
private const val ROOM_END: Int = 3

/** Bob's attacking creature across the CR 701.51c scenarios. */
private val BOB_ATTACKER = ObjectId(10)

/** Bob's second attacker, for the "one or more creatures" reading. */
private val BOB_SECOND_ATTACKER = ObjectId(11)

/** A room ability that does nothing — these specs test the *walk*, not what the rooms do. */
private fun inertRoom(
    name: String,
    successors: List<Int>,
): DungeonRoom =
    DungeonRoom(
        name = name,
        ability =
            DungeonRoomAbility.Runs(
                TriggeredAbility(
                    condition = TriggerCondition.EnteredDungeonRoom,
                    effect = ResolutionEffect { state, _ -> state },
                    zoneScope = TriggerZoneScope.Command,
                ),
            ),
        successors = successors.toPersistentListOfInts(),
    )

/**
 * The Undercity's shape in miniature: an entrance that branches to two middles, both of which rejoin at
 * a terminal room. Every rule these specs exercise — the branch decision, the rejoin, the completion —
 * is a property of that shape rather than of any particular room's ability.
 */
private val fixtureDungeon: Dungeon =
    Dungeon(
        name = "Fixture Dungeon",
        rooms =
            persistentListOf(
                inertRoom("Entrance", listOf(ROOM_LEFT, ROOM_RIGHT)),
                inertRoom("Left", listOf(ROOM_END)),
                inertRoom("Right", listOf(ROOM_END)),
                inertRoom("End", emptyList()),
            ),
    )

/** A one-room dungeon whose only room the engine cannot run — the CR 309.5 loud-failure fixture. */
private val brokenDungeon: Dungeon =
    Dungeon(
        name = "Broken Dungeon",
        rooms =
            persistentListOf(
                DungeonRoom(
                    name = "Locked Door",
                    ability =
                        DungeonRoomAbility.Unimplemented(
                            printed = "Open the door.",
                            diagnosis = "a framework this engine does not have",
                        ),
                ),
            ),
    )

/** A bare two-player state with no initiative, no permanents, and nothing on the stack. */
private fun baseState(): GameState =
    GameState(
        players =
            persistentMapOf(
                alice to emptySeat(alice),
                bob to emptySeat(bob),
            ),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield =
                    persistentListOf(
                        GameObject(BOB_ATTACKER, CardRef("Fixture Attacker"), bob),
                        GameObject(BOB_SECOND_ATTACKER, CardRef("Fixture Attacker"), bob),
                    ),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = 100,
        rng = Rng(0),
        events = persistentListOf(),
    )

/** A seated player with a two-card library, enough for any room ability that draws. */
private fun emptySeat(seat: PlayerId): PlayerState =
    PlayerState(
        life = STARTING_LIFE,
        library =
            persistentListOf(
                GameObject(ObjectId(seat.seat * 2L + 20), CardRef("Mountain"), seat),
                GameObject(ObjectId(seat.seat * 2L + 21), CardRef("Mountain"), seat),
            ),
        hand = persistentListOf(),
        graveyard = persistentListOf(),
    )

/**
 * Resolves [seat]'s pending venture ability, asserting it did not pause on a branch — the unbranched
 * walk, used wherever the branch itself is not the subject.
 */
private fun ventureOnce(
    state: GameState,
    seat: PlayerId,
): GameState {
    val resolved = stateOf(ventureFrom(state, seat))
    check(resolved.pendingVenture == null) { "this venture branched; use ventureFrom and answer the choice" }
    return resolved
}

/**
 * Fires [seat]'s CR 701.51b upkeep venture and resolves it — the way a dungeon is actually walked past
 * its first room, and the only path that does not also re-take the initiative.
 */
private fun ventureAgainAtUpkeep(
    state: GameState,
    seat: PlayerId,
): AdvanceResult = ventureFrom(enqueueUpkeepVenture(state.copy(pendingTriggers = persistentListOf()), seat), seat)

/** Puts [seat]'s pending venture ability on the stack and resolves it (CR 608.1). */
private fun ventureFrom(
    state: GameState,
    seat: PlayerId,
): AdvanceResult {
    val trigger =
        state.pendingTriggers.firstOrNull {
            it.controller == seat && it.ability.condition == TriggerCondition.VentureIntoDungeon
        } ?: error("no venture ability is pending for $seat")
    val entry = StackEntry.Ability(trigger)
    val onStack =
        state.copy(
            pendingTriggers = state.pendingTriggers.removing(trigger),
            sharedZones = state.sharedZones.copy(stack = state.sharedZones.stack.adding(entry)),
        )
    return resolveVentureTrigger(onStack, entry)
}

/** The topmost CR 309.5 room ability on [state]'s stack; fails if the venture put none there. */
private fun roomAbilityOnStack(state: GameState): StackEntry.Ability =
    state.sharedZones.stack
        .filterIsInstance<StackEntry.Ability>()
        .lastOrNull { it.trigger.ability.condition == TriggerCondition.EnteredDungeonRoom }
        ?: error("CR 309.5: entering a room puts its ability on the stack, but none is there")

/** The state inside [result], whatever kind of pause or continuation it is. */
private fun stateOf(result: AdvanceResult): GameState =
    when (result) {
        is AdvanceResult.NeedsDecision -> result.state
        is AdvanceResult.GameOver -> result.state
    }

/** Walks [seat]'s marker to [room] directly — a shortcut past ventures whose steps are tested above. */
private fun markerOn(
    state: GameState,
    seat: PlayerId,
    room: Int,
): GameState {
    val initiative = state.initiative ?: error("no initiative to place a marker in")
    val marker =
        dev.mtgplay.core.state
            .VentureMarker(room, ObjectId(90 + seat.seat.toLong()))
    return state.copy(initiative = initiative.copy(markers = initiative.markers.putting(seat, marker)))
}

/** The successor indices as the persistent list [DungeonRoom] takes. */
private fun List<Int>.toPersistentListOfInts() = persistentListOf<Int>().addingAll(this)
