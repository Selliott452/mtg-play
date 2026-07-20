package dev.mtgplay.rules.engine

import dev.mtgplay.core.state.GameState

/** The maximum hand size players discard down to during cleanup (CR 402.2, CR 514.1). */
internal const val MAXIMUM_HAND_SIZE: Int = 7

/**
 * The untap step's turn-based actions (CR 502): phasing and untapping the active player's
 * permanents, performed before anyone could receive priority.
 *
 * A documented no-op hook in P1.2: nothing can be tapped (or phased) until Phase 3 introduces
 * permanents on the battlefield with a tapped status. The hook exists so the untap step's
 * turn-based-action slot is real in the state machine — Phase 3 fills it in without reshaping
 * the advance loop.
 */
internal fun untapStepTurnBasedActions(state: GameState): GameState = state

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
