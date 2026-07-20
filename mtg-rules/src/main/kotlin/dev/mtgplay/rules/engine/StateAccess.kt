package dev.mtgplay.rules.engine

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.StackEntry
import kotlinx.collections.immutable.PersistentList

/** Appends [event] to the derived event log (ADR-006); pure, like every transition. */
internal fun GameState.emit(event: GameEvent): GameState = copy(events = events.adding(event))

/** Replaces the stack via [transform]; the last element stays the top (CR 405). */
internal fun GameState.updateStack(transform: (PersistentList<StackEntry>) -> PersistentList<StackEntry>): GameState =
    copy(sharedZones = sharedZones.copy(stack = transform(sharedZones.stack)))

/** This seat's state; fails loudly if [id] is not seated — the engine never guesses. */
internal fun GameState.player(id: PlayerId): PlayerState = players[id] ?: error("player $id is not seated in this game")

/** Returns a state with [id]'s player state replaced by `transform` of the current one. */
internal fun GameState.updatePlayer(
    id: PlayerId,
    transform: (PlayerState) -> PlayerState,
): GameState = copy(players = players.putting(id, transform(player(id))))

/**
 * The next seat after [seat] in turn order, wrapping around. The players map's insertion order
 * is turn order (see [GameState.players]), and APNAP order (CR 101.4) derives from it.
 */
internal fun GameState.seatAfter(seat: PlayerId): PlayerId {
    val order = players.keys.toList()
    val index = order.indexOf(seat)
    require(index >= 0) { "player $seat is not seated in this game" }
    return order[(index + 1) % order.size]
}
