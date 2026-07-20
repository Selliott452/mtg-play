package dev.mtgplay.rules.engine

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState

/**
 * Changes [player]'s life total by [delta] — negative for losing life (CR 119.3), positive for
 * gaining it (CR 119.2) — emitting [GameEvent.LifeChanged]. The total may legally land at or
 * below 0: the CR 704.5a state-based action acts later, whenever a player would next receive
 * priority (CR 704.3), never here.
 */
internal fun changeLife(
    state: GameState,
    player: PlayerId,
    delta: Int,
): GameState {
    val newTotal = state.player(player).life + delta
    return state
        .updatePlayer(player) { it.copy(life = newTotal) }
        .emit(GameEvent.LifeChanged(player, delta, newTotal))
}
