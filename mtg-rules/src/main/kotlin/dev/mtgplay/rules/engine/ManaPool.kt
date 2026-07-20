package dev.mtgplay.rules.engine

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameState
import kotlinx.collections.immutable.persistentListOf

/*
 * Mana-pool bookkeeping (CR 106.4): mana enters a pool when a mana ability resolves
 * (CR 605.3), leaves it when a cost is paid (CR 601.2h), and any remainder empties when each
 * step and phase ends (CR 500.4).
 */

/** Adds one [mana] to [player]'s pool, emitting [GameEvent.ManaAdded] (CR 106.4). */
internal fun addManaToPool(
    state: GameState,
    player: PlayerId,
    mana: ManaType,
): GameState =
    state
        .updatePlayer(player) { it.copy(manaPool = it.manaPool.adding(mana)) }
        .emit(GameEvent.ManaAdded(player, mana))

/**
 * Removes one mana of type [mana] from [player]'s pool — a payment consuming it (CR 601.2h).
 * Fails loudly if no such mana is pooled: a payment plan only demands mana its own activations
 * put there, so a miss is an engine defect, never a player error (ADR-005).
 */
internal fun removeManaFromPool(
    state: GameState,
    player: PlayerId,
    mana: ManaType,
): GameState {
    val pool = state.player(player).manaPool
    val index = pool.indexOfFirst { it == mana }
    require(index >= 0) { "CR 601.2h: payment demands a $mana from $player's pool, but the pool holds $pool" }
    return state.updatePlayer(player) { it.copy(manaPool = it.manaPool.removingAt(index)) }
}

/**
 * Empties every player's mana pool because the current step or phase is ending (CR 500.4),
 * emitting [GameEvent.ManaPoolEmptied] for each pool that actually held mana. Seats are
 * processed in turn order (the players map's insertion order) for a deterministic event log.
 */
internal fun emptyManaPoolsAtPositionEnd(state: GameState): GameState =
    state.players.entries.fold(state) { current, (seat, player) ->
        if (player.manaPool.isEmpty()) {
            current
        } else {
            current
                .updatePlayer(seat) { it.copy(manaPool = persistentListOf()) }
                .emit(GameEvent.ManaPoolEmptied(seat))
        }
    }
