package dev.mtgplay.rules.effect

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.updatePlayer

/**
 * Effect primitive: schedules a skip of [player]'s **next** combat phase (CR 500.10) — Stonehorn
 * Dignitary's "target opponent skips their next combat phase" (`W8-G`).
 *
 * A primitive rather than something a card writes, because what it schedules is a modification of the
 * *turn structure* and nothing else in the engine may write one. It is the player-level sibling of
 * [skipNextUntapStep], and the differences between the two are the whole design:
 *
 * - **It counts rather than marks.** `skipNextUntapStep` absorbs a repeat ("the marker is a fact, not a
 *   counter") because a permanent either does or does not untap. Two Dignitaries owe their victim two
 *   combat phases, and blinking one — the line UWX Familiar actually plays with Ephemerate and Ghostly
 *   Flicker — owes another, so each application adds.
 * - **It lives on the player, not on a permanent.** The obligation outlives the Dignitary: killing it in
 *   response to the trigger, or on the turn after, does nothing to a skip already scheduled, because the
 *   trigger has already resolved and CR 500.10 skips are not continuous effects (docs/design/duration.md
 *   §12 lists "skips their next combat phase" among the *non*-goals of the layer/duration machinery for
 *   exactly this reason: it modifies the turn structure, not any object's characteristics).
 * - **"Their next" is spent by the affected player's own turn**, since a combat phase belongs to whoever
 *   is active (CR 506.1). The engine consumes it in `spendScheduledCombatSkip`, at the one transition
 *   that steps over the phase.
 *
 * **The skip is scheduled, never immediate.** A Dignitary that resolves during the opponent's *own*
 * combat phase does not end it — CR 500.10 skipping proceeds past a phase that has not begun, and the
 * one in progress has. The counter is read only as the walk leaves a precombat main phase, which is what
 * makes that come out right without the card saying anything about it.
 */
fun skipNextCombatPhase(
    state: GameState,
    player: PlayerId,
): GameState = state.updatePlayer(player) { it.copy(combatPhasesToSkip = it.combatPhasesToSkip + 1) }
