package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.emit
import dev.mtgplay.rules.engine.updateBattlefield

/**
 * Effect primitive: taps the battlefield permanent [objectId] (CR 701.21a) — the published building block
 * a "tap target permanent" resolution composes (ADR-003), the way removal composes [destroy] and bounce
 * composes [returnPermanentToOwnersHand]. Harrier Strix's enters-the-battlefield trigger is the first
 * client.
 *
 * **Tapping an already-tapped permanent does nothing** (CR 701.21a: "only an untapped permanent can be
 * tapped"), and does so *silently* rather than loudly: unlike a target that has vanished, a tapped target
 * is a perfectly legal target that the effect simply cannot affect, which is a game state the CR expects
 * rather than an engine defect. No [GameEvent.ObjectTapped] is emitted in that case, because no permanent
 * became tapped — the log would otherwise claim a status change that did not happen.
 *
 * **It is not a cost and it is not `{T}`.** The `{T}` symbol (CR 107.5) is an *activation cost* paid by
 * the source of an ability, which the activation pipeline pays directly; this is an *effect* that taps
 * some other permanent, and the two are deliberately separate. In particular this ignores summoning
 * sickness (CR 302.6), which restricts only the `{T}` cost of a creature's own ability, never an effect
 * tapping it — a creature that entered this turn can be tapped down by Harrier Strix.
 *
 * Fails loudly if [objectId] is not on the battlefield: every caller reaches this after the CR 608.2b
 * re-check has confirmed its target is still a legal battlefield permanent (ADR-005), so a missing one is
 * an engine defect rather than a fizzle to absorb.
 */
fun tapPermanent(
    state: GameState,
    objectId: ObjectId,
): GameState {
    val index = state.sharedZones.battlefield.indexOfFirst { it.id == objectId }
    require(index >= 0) {
        "CR 701.21a: only a permanent on the battlefield can be tapped, but $objectId is not"
    }
    val permanent = state.sharedZones.battlefield[index]
    // CR 701.21a: tapping an already-tapped permanent has no effect, and narrates none.
    if (permanent.tapped) return state
    return state
        .updateBattlefield { it.removingAt(index).addingAt(index, permanent.copy(tapped = true)) }
        .emit(GameEvent.ObjectTapped(objectId, permanent.card))
}
