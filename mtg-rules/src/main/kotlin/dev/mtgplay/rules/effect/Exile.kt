package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.clearCombatReferences
import dev.mtgplay.rules.engine.emit
import dev.mtgplay.rules.engine.updateBattlefield
import dev.mtgplay.rules.engine.updateExile

/**
 * Effect primitive: **exiles** the battlefield permanent [objectId] (CR 701.3a) — the published
 * building block an "exile target …" resolution composes (ADR-003; Scour from Existence and Last
 * Breath are the first clients).
 *
 * The permanent moves from the battlefield to the shared exile zone as a new object (CR 400.7),
 * carrying no battlefield memory — untapped, no marked damage, attached to nothing — and emitting
 * [GameEvent.PermanentExiled]. Combat then releases it (CR 506.4). The exiled object is face up and
 * ownerless of any special status: nothing marks it for a later return, because no card in the pool
 * brings it back.
 *
 * **Exiling is not destroying, and the differences are load-bearing.** Indestructible (CR 702.12b)
 * does not stop it, so an "exile target permanent" answers a Bridge that a "destroy target artifact"
 * cannot. Nothing goes to a graveyard, so no CR 603.6b "put into a graveyard from the battlefield"
 * trigger is detected — deliberately, and the reason this is a separate primitive rather than a
 * parameter on [destroy]. An Aura on the exiled permanent is untouched here and falls off at the
 * next CR 704.5m state-based check, and a token exiled this way ceases to exist at the next CR 704.5d
 * check.
 *
 * This is the battlefield exile only. The other three exiles in the engine each have their own path
 * because each *replaces* a different move — madness replaces a discard (CR 702.35a), flashback
 * replaces a leave-the-stack move (CR 702.34e), escape's cost exiles from a graveyard (CR 601.2h) —
 * and CR 701.3a is the one that is an effect in its own right.
 *
 * Fails loudly if [objectId] is not on the battlefield: every caller reaches this after the
 * CR 608.2b re-check has confirmed its target is still a legal battlefield permanent (ADR-005), so a
 * missing one is an engine defect, not a rules case.
 */
fun exilePermanent(
    state: GameState,
    objectId: ObjectId,
): GameState {
    val battlefield = state.sharedZones.battlefield
    val index = battlefield.indexOfFirst { it.id == objectId }
    require(index >= 0) { "CR 701.3a: an exiled permanent must be on the battlefield, but $objectId is not" }
    val permanent = battlefield[index]
    val (exileId, allocated) = state.allocateObjectId()
    val reborn = GameObject(id = exileId, card = permanent.card, owner = permanent.owner)
    val moved =
        allocated
            .updateBattlefield { it.removingAt(index) }
            .updateExile { it.adding(reborn) }
            .emit(GameEvent.PermanentExiled(objectId, permanent.card, exileId))
    // CR 506.4: an exiled permanent that was in combat is removed from it.
    return clearCombatReferences(moved, setOf(objectId))
}
