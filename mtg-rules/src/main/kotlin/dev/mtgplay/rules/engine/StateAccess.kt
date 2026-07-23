package dev.mtgplay.rules.engine

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.CombatState
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.StackEntry
import kotlinx.collections.immutable.PersistentList

/** Appends [event] to the derived event log (ADR-006); pure, like every transition. */
internal fun GameState.emit(event: GameEvent): GameState = copy(events = events.adding(event))

/**
 * Replaces the current combat state via [transform] (CR 506–511); fails loudly if no combat is in
 * progress, since every combat transition runs while [dev.mtgplay.core.state.Turn.combat] is set.
 */
internal fun GameState.updateCombat(transform: (CombatState) -> CombatState): GameState {
    val combat = turn.combat ?: error("CR 506.1: no combat is in progress")
    return copy(turn = turn.copy(combat = transform(combat)))
}

/** The sole opponent of [seat] in a two-player game (CR 508.1's defending player). */
internal fun GameState.opponentOf(seat: PlayerId): PlayerId =
    players.keys.singleOrNull { it != seat }
        ?: error("CR 508.1: a two-player defending player is required; seats are ${players.keys}")

/** Replaces the stack via [transform]; the last element stays the top (CR 405). */
internal fun GameState.updateStack(transform: (PersistentList<StackEntry>) -> PersistentList<StackEntry>): GameState =
    copy(sharedZones = sharedZones.copy(stack = transform(sharedZones.stack)))

/** Replaces the battlefield via [transform] (CR 403); insertion order is kept for determinism. */
internal fun GameState.updateBattlefield(
    transform: (PersistentList<GameObject>) -> PersistentList<GameObject>,
): GameState = copy(sharedZones = sharedZones.copy(battlefield = transform(sharedZones.battlefield)))

/** Replaces the exile zone via [transform] (CR 406); insertion order is kept for determinism. */
internal fun GameState.updateExile(transform: (PersistentList<GameObject>) -> PersistentList<GameObject>): GameState =
    copy(sharedZones = sharedZones.copy(exile = transform(sharedZones.exile)))

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
