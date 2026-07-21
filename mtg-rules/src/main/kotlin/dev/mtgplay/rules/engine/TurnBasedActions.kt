package dev.mtgplay.rules.engine

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.state.GameState
import kotlinx.collections.immutable.toPersistentList

/** The maximum hand size players discard down to during cleanup (CR 402.2, CR 514.1). */
internal const val MAXIMUM_HAND_SIZE: Int = 7

/**
 * The untap step's turn-based actions (CR 502): the active player untaps their tapped
 * permanents (CR 502.2, all at once), before anyone could receive priority (CR 502.4). The
 * simultaneous untap emits one [GameEvent.ObjectUntapped] per object that was tapped, in
 * battlefield order, for a deterministic log (P2.2).
 *
 * Controller is owner until control-changing effects exist (Phase 4+). Phasing (CR 502.1)
 * remains a documented gap: nothing in the MVP pool phases, and an unrepresentable status
 * cannot be silently mishandled.
 */
internal fun untapStepTurnBasedActions(state: GameState): GameState {
    val active = state.turn.activePlayer
    val untapping = state.sharedZones.battlefield.filter { it.owner == active && it.tapped }
    val untapped =
        state.sharedZones.battlefield
            .map { obj -> if (obj.owner == active && obj.tapped) obj.copy(tapped = false) else obj }
            .toPersistentList()
    return untapping.fold(
        state.copy(sharedZones = state.sharedZones.copy(battlefield = untapped)),
    ) { current, obj -> current.emit(GameEvent.ObjectUntapped(obj.id, obj.card)) }
}

/**
 * The draw step's turn-based action (CR 504.1): the active player draws a card. Happens before
 * any player receives priority in the step (CR 504.2); a failed draw from an empty library is
 * recorded by [drawCard] for the CR 704.5c state-based action.
 */
internal fun drawStepTurnBasedAction(state: GameState): GameState = drawCard(state, state.turn.activePlayer)

/**
 * The cleanup step's simultaneous turn-based actions after the discard (CR 514.2): remove all
 * marked damage and end "until end of turn" / "this turn" effects.
 *
 * A documented no-op hook in P1.2: damage does not exist until Phase 3 and until-end-of-turn
 * effects until Phase 4. As with [untapStepTurnBasedActions], the slot is real so later phases
 * fill it in place.
 */
internal fun cleanupRemoveDamageAndEndEffects(state: GameState): GameState = state
