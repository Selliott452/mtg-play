package dev.mtgplay.rules.engine

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.state.EffectDuration
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
 * The cleanup step's **simultaneous** turn-based actions after the discard (CR 514.2): remove all
 * marked damage and end every "until end of turn" continuous effect, in one transition.
 *
 * Both halves are now real. All damage marked on battlefield objects (CR 120.3d) wears off, and
 * every [dev.mtgplay.core.state.EffectDuration.UntilEndOfTurn] effect leaves
 * [GameState.timedEffects] (`FW-DURATION`, docs/design/duration.md §5.4).
 *
 * **The simultaneity is load-bearing, not a formality.** A 1/2 pumped to a 4/5 that took 4 combat
 * damage survives (4 < 5). If the pump ended *before* the damage cleared, the creature would
 * momentarily be a 1/2 with 4 marked damage and die to the CR 704.5g state-based action — a
 * reachable, silently-wrong death. Doing both in a single state transition, before
 * `performStateBasedActions` runs, is what makes CR 514.2's "simultaneously" true here.
 *
 * No CR 514.2 wear-off in the implemented effect set can make a state-based action applicable, so
 * the CR 514.3a repeat-cleanup path is unreachable *from a duration*: a positive P/T modifier ending
 * lowers toughness only in the same instant all damage is removed, and a negative one ending raises
 * it. The first effect kind that breaks that (a set-P/T wearing off) reaches the existing repeat
 * path, which already works.
 *
 * The `when` over [dev.mtgplay.core.state.EffectDuration] is exhaustive so a new duration cannot
 * default into ending here.
 *
 * No event narrates the wear-off — like the untap step's status change it is silent bookkeeping, and
 * the acceptance invariant checker confirms that neither marked damage nor a timed effect survives a
 * completed turn.
 */
internal fun cleanupRemoveDamageAndEndEffects(state: GameState): GameState {
    val cleared =
        state.sharedZones.battlefield
            .map { obj ->
                // CR 514.2: the deathtouch record is part of the marked damage it describes, so it is
                // wiped in the same transition rather than outliving the damage that set it.
                if (obj.damageMarked != 0 || obj.dealtDeathtouchDamage) {
                    obj.copy(damageMarked = 0, dealtDeathtouchDamage = false)
                } else {
                    obj
                }
            }.toPersistentList()
    val surviving =
        state.timedEffects
            .filterNot { effect ->
                when (effect.duration) {
                    EffectDuration.UntilEndOfTurn -> true
                }
            }.toPersistentList()
    return state.copy(
        sharedZones = state.sharedZones.copy(battlefield = cleared),
        timedEffects = surviving,
    )
}
