package dev.mtgplay.rules.engine

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.state.EffectDuration
import dev.mtgplay.core.state.GameObject
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
 * **A permanent marked [GameObject.skipsNextUntapStep] does not untap, and spends its marker here**
 * (CR 502.2) — Sleep of the Dead's "It doesn't untap during its controller's next untap step".
 * Additive (`FW-TAPUNTAP`). Three details are load-bearing and all three are the CR's:
 * - The marker is spent **whether or not the permanent was tapped**. "Its controller's next untap step"
 *   names a step, not an event: an untapped permanent's Sleep rider is used up by the very next untap
 *   step doing nothing, and does not lie in wait for a later one.
 * - It is spent only in **its controller's** untap step, which is why the clearing is scoped to the
 *   active player exactly as the untapping is. Controller is owner until control-changing effects
 *   exist (Phase 4+).
 * - A skipped permanent emits **no** [GameEvent.ObjectUntapped], because it did not untap. The event
 *   list is derived observability (ADR-006) and must not narrate a status change that never happened.
 *
 * Phasing (CR 502.1) remains a documented gap: nothing in the MVP pool phases, and an unrepresentable
 * status cannot be silently mishandled.
 */
internal fun untapStepTurnBasedActions(state: GameState): GameState {
    val active = state.turn.activePlayer

    // CR 502.2: the active player's permanents untap, except those a "doesn't untap" effect holds down.
    fun untaps(obj: GameObject): Boolean = obj.owner == active && obj.tapped && !obj.skipsNextUntapStep
    val untapping = state.sharedZones.battlefield.filter(::untaps)
    val stepped =
        state.sharedZones.battlefield
            .map { obj ->
                when {
                    untaps(obj) -> obj.copy(tapped = false)
                    // The marker names *this* step and is spent by it, tapped or not (see the KDoc).
                    obj.owner == active && obj.skipsNextUntapStep -> obj.copy(skipsNextUntapStep = false)
                    else -> obj
                }
            }.toPersistentList()
    return untapping.fold(
        state.copy(sharedZones = state.sharedZones.copy(battlefield = stepped)),
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
    // CR 118.5: "until the end of your next turn" — a play permission granted on an earlier turn ends
    // at the cleanup of the first later turn that is its owner's. Read through the same
    // [playGrantHasExpired] the enumeration uses, so an expired permission cannot be offered and a live
    // one cannot be cleared early.
    val exile =
        state.sharedZones.exile
            .map { obj ->
                val granted = obj.playGrantedTurn
                if (granted != null && playGrantHasExpired(state, obj.owner, granted)) {
                    obj.copy(playGrantedTurn = null)
                } else {
                    obj
                }
            }.toPersistentList()
    return state.copy(
        sharedZones = state.sharedZones.copy(battlefield = cleared, exile = exile),
        timedEffects = surviving,
    )
}
