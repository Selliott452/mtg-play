package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.Dungeon
import dev.mtgplay.core.definition.DungeonRoom
import dev.mtgplay.core.definition.DungeonRoomAbility
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingTrigger
import dev.mtgplay.core.state.PendingVenture
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.VentureMarker
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId

/*
 * **Dungeons** (CR 309) and the venture keyword action (CR 701.49) — `W10-A`. The initiative that walks
 * a player through one is `Initiative.kt`.
 *
 * A dungeon is a card that never enters an ordinary zone: it waits in its player's command zone
 * (CR 309.2) while a venture marker moves along its room graph. Everything the engine does with one is
 * here, and it is three moves:
 *
 * 1. **Move the marker** ([venture], CR 309.4) — enter at the first room, or advance to the next one,
 *    pausing for the player's choice where a room leads to two (ADR-005).
 * 2. **Trigger the room** ([enterRoom], CR 309.5) — a room's ability is an ordinary triggered ability
 *    that uses the stack, can be responded to, and can target, so it goes through the same placement and
 *    resolution as any printed trigger.
 * 3. **Remove a finished dungeon** ([completeDungeon], CR 309.6) — after which the player is in no
 *    dungeon and their next venture starts over at the first room.
 *
 * **Why the venture is engine-orchestrated rather than a [dev.mtgplay.core.definition.ResolutionEffect].**
 * A branching room asks its player which way to go, and ADR-004 forbids a resolution effect making a
 * decision. So the venture ability joins madness, rebound, cascade and storm in
 * [resolveOrchestratedTrigger]: its declared effect is a no-op and the engine performs the keyword
 * action.
 *
 * **`mtg-rules` names no dungeon** (ADR-003). Everything here walks an arbitrary
 * [dev.mtgplay.core.definition.Dungeon] value read out of the game state; the Undercity is a value
 * declared in `mtg-cards` and handed in once, by the card that prints "you take the initiative".
 */

/**
 * Resolves a venture ability (CR 701.49, CR 309.4): the ability leaves the stack (CR 113.7a), then its
 * controller's venture marker moves — pausing on [GameState.pendingVenture] where the room branches.
 * Called from [resolveOrchestratedTrigger] when the resolving ability's condition is
 * [TriggerCondition.VentureIntoDungeon].
 */
internal fun resolveVentureTrigger(
    state: GameState,
    entry: StackEntry.Ability,
): AdvanceResult {
    check(state.sharedZones.stack.lastOrNull() == entry) { "CR 608.1: only the topmost stack object may resolve" }
    val trigger = entry.trigger
    val ceased =
        state
            .updateStack { it.removingAt(it.lastIndex) }
            .emit(GameEvent.TriggeredAbilityResolved(trigger.controller, trigger.sourceCard))
    return venture(ceased, trigger.controller)
}

/**
 * Performs the venture keyword action for [player] (CR 701.49, CR 309.4): enter the dungeon at its first
 * room, or advance the marker to the next one — asking which where the room branches.
 *
 * **A completed dungeon is cleared here, at the next venture** (CR 309.6). The rule says the dungeon card
 * is removed once the last room's abilities have left the stack, and this is one step later than that.
 * The two are the same game: a venture is caused either by a stack object below the room's ability
 * resolving or by a later upkeep's turn-based action, so the last room's ability has *always* left the
 * stack before another venture can begin. What the deviation costs is one narrower thing — between those
 * two moments the state still says the player is in the last room — and nothing in the rules or in any
 * decision reads that, since a venture marker has exactly one reader, which is this function.
 */
private fun venture(
    state: GameState,
    player: PlayerId,
): AdvanceResult {
    val initiative = state.initiative ?: error("CR 701.49: a venture needs a dungeon to venture into")
    val room = initiative.roomOf(player)
    val successors = room?.let { initiative.dungeon.rooms[it].successors }.orEmpty()
    return when {
        // CR 309.4: not in a dungeon — enter at the first room. Also the completed-dungeon case, whose
        // marker is on a room with no successors and is cleared by the entry below (CR 309.6).
        room == null || successors.isEmpty() -> enterRoom(completeDungeon(state, player), player, 0)
        successors.size == 1 -> enterRoom(state, player, successors.single())
        // CR 309.4: the room leads to two, so its player chooses (ADR-005).
        else -> {
            val paused = state.copy(pendingVenture = PendingVenture(player, room))
            AdvanceResult.NeedsDecision(paused, pendingVentureRequest(paused))
        }
    }
}

/**
 * Removes [player]'s completed dungeon (CR 309.6) when their marker is on a room with no successors, and
 * returns [state] unchanged otherwise. Narrated, because completing a dungeon is public and is what makes
 * the next venture start over.
 */
private fun completeDungeon(
    state: GameState,
    player: PlayerId,
): GameState {
    val initiative = state.initiative
    val room = initiative?.roomOf(player)
    if (initiative == null || room == null || !initiative.dungeon.isLastRoom(room)) return state
    return state
        .copy(initiative = initiative.copy(markers = initiative.markers.removing(player)))
        .emit(GameEvent.DungeonCompleted(player, initiative.dungeon.name))
}

/**
 * Puts [player]'s venture marker on [room] (CR 309.4) and triggers that room's ability (CR 309.5), then
 * hands back a priority round in which the trigger is placed on the stack (CR 603.3b).
 *
 * **A room whose printed ability this engine cannot express fails loudly here** rather than being entered
 * and quietly doing nothing (CONVENTIONS.md). It is unreachable in a built game: a dungeon holding such a
 * room ([Dungeon.unimplementedRooms]) must not be reachable from any registered card, which is pinned in
 * `mtg-cards`. This is the check that makes "must not" mean something.
 */
private fun enterRoom(
    state: GameState,
    player: PlayerId,
    room: Int,
): AdvanceResult {
    val initiative = state.initiative ?: error("CR 309.4: a venture marker needs a dungeon to sit on")
    val dungeon = initiative.dungeon
    val entered = dungeon.rooms[room]
    // CR 309.2: entering a dungeon puts its card into the player's command zone as a new object
    // (CR 400.7); advancing within one keeps the card, and so keeps its id.
    val existing = initiative.markers[player]
    val (dungeonObjectId, allocated) = existing?.let { it.dungeonObjectId to state } ?: state.allocateObjectId()
    val standing = allocated.initiative ?: error("CR 309.4: the initiative cannot vanish mid-venture")
    val moved =
        allocated
            .copy(
                initiative =
                    standing.copy(markers = standing.markers.putting(player, VentureMarker(room, dungeonObjectId))),
            ).emit(GameEvent.VenturedIntoDungeon(player, dungeon.name, entered.name))
    return grantPriorityRound(enqueueRoomTrigger(moved, player, entered, dungeonObjectId))
}

/** Enqueues [room]'s CR 309.5 triggered ability for [player], whose marker has just been put on it. */
private fun enqueueRoomTrigger(
    state: GameState,
    player: PlayerId,
    room: DungeonRoom,
    dungeonObjectId: dev.mtgplay.core.identity.ObjectId,
): GameState {
    val initiative = state.initiative ?: error("CR 309.5: a room trigger needs a dungeon to be a source")
    val ability =
        when (val declared = room.ability) {
            is DungeonRoomAbility.Runs -> declared.ability
            is DungeonRoomAbility.Unimplemented ->
                error(
                    "CR 309.5: ${initiative.dungeon.name}'s room \"${room.name}\" reads " +
                        "\"${declared.printed}\", which this engine cannot run: ${declared.diagnosis}. " +
                        "A dungeon with an unimplemented room must not be reachable from a registered card",
                )
        }
    return enqueuePendingTrigger(
        state,
        PendingTrigger(
            sourceId = dungeonObjectId,
            sourceCard = CardRef(initiative.dungeon.name),
            controller = player,
            ability = ability,
        ),
    )
}

/**
 * The branch request the open [GameState.pendingVenture] is waiting on (CR 309.4). A pure function of the
 * state (ADR-004): the options are the room's printed successors, which nothing can change while the
 * pause is open.
 */
internal fun pendingVentureRequest(state: GameState): DecisionRequest.ChooseDungeonRoom {
    val pending = state.pendingVenture ?: error("no venture branch choice is pending")
    val initiative = state.initiative ?: error("CR 309.4: a pending venture needs a dungeon")
    val from = initiative.dungeon.rooms[pending.fromRoom]
    return DecisionRequest.ChooseDungeonRoom(
        id = DecisionRequestId(pending.player, state.player(pending.player).decisionsAnswered),
        dungeon = initiative.dungeon.name,
        fromRoom = from.name,
        options =
            from.successors.map { index ->
                DecisionRequest.ChooseDungeonRoom.Option(index, initiative.dungeon.rooms[index].name)
            },
    )
}

/**
 * Applies the venturing player's chosen room (CR 309.4): the marker moves there and that room's ability
 * triggers (CR 309.5), exactly as it would have on an unbranched move.
 */
internal fun applyVentureRoomChoice(
    state: GameState,
    room: Int,
): AdvanceResult {
    val pending = state.pendingVenture ?: error("no venture branch choice is pending")
    return enterRoom(state.copy(pendingVenture = null), pending.player, room)
}
