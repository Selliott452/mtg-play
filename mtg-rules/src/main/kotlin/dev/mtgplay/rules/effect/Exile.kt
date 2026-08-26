package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.announceBattlefieldDeparture
import dev.mtgplay.rules.engine.clearCombatReferences
import dev.mtgplay.rules.engine.emit
import dev.mtgplay.rules.engine.rememberLastKnownPower
import dev.mtgplay.rules.engine.updateBattlefield
import dev.mtgplay.rules.engine.updateExile

/**
 * Effect primitive: **exiles** the battlefield permanent [objectId] (CR 701.3a) — the published
 * building block an "exile target …" resolution composes (ADR-003; Scour from Existence and Last
 * Breath are the first clients).
 *
 * The permanent moves from the battlefield to the shared exile zone as a new object (CR 400.7),
 * carrying no battlefield memory — untapped, no marked damage, attached to nothing, no counters
 * (CR 122.2), and no linked-exile record of its own (CR 607.3) — and emitting
 * [GameEvent.PermanentExiled]. Combat then releases it (CR 506.4).
 *
 * **Exiling is not destroying, and the differences are load-bearing.** Indestructible (CR 702.12b)
 * does not stop it, so an "exile target permanent" answers a Bridge that a "destroy target artifact"
 * cannot. Nothing goes to a graveyard, so no CR 603.6b "put into a graveyard from the battlefield"
 * trigger is detected — deliberately, and the reason this is a separate primitive rather than a
 * parameter on [destroy]. The **general** CR 603.6c leaves-the-battlefield trigger *is* detected, via
 * [announceBattlefieldDeparture]: exiling a Journey to Nowhere returns the creature it was holding,
 * and getting that wrong was the trap `FW-TRIGLTB` exists to close. An Aura on the exiled permanent is
 * untouched here and falls off at the next CR 704.5m state-based check, and a token exiled this way
 * ceases to exist at the next CR 704.5d check.
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
): GameState = exilePermanentReturningId(state, objectId).state

/**
 * [exilePermanent], additionally reporting the **new exile object's id** (CR 400.7) so a caller that
 * must remember what it exiled can do so.
 *
 * Its own entry point rather than a widened return on [exilePermanent] because the two callers want
 * genuinely different things and only one of them should have to name the extra value: an "exile target
 * permanent" resolution (Scour from Existence) exiles and forgets, while a **linked** ability
 * (CR 607.2 — Journey to Nowhere) must write the id onto its own source, and a flicker (Ephemerate)
 * must hand it straight to the return. Both of those are engine-side compositions, which is why this is
 * `internal` and the forgetting one stays the published primitive (ADR-003).
 */
internal fun exilePermanentReturningId(
    state: GameState,
    objectId: ObjectId,
): ExiledPermanent {
    val battlefield = state.sharedZones.battlefield
    val index = battlefield.indexOfFirst { it.id == objectId }
    require(index >= 0) { "CR 701.3a: an exiled permanent must be on the battlefield, but $objectId is not" }
    val permanent = battlefield[index]
    // CR 608.2h: the layered power this permanent leaves with, for a reader that resolves after it
    // is gone (Monstrous Emergence). Captured before the removal — it cannot be computed after.
    val (exileId, allocated) = rememberLastKnownPower(state, objectId).allocateObjectId()
    val reborn = GameObject(id = exileId, card = permanent.card, owner = permanent.owner)
    val moved =
        allocated
            .updateBattlefield { it.removingAt(index) }
            .updateExile { it.adding(reborn) }
            .emit(GameEvent.PermanentExiled(objectId, permanent.card, exileId))
    // CR 603.6c, CR 603.10: a leaves-the-battlefield trigger fires against the pre-exile state.
    val triggered = announceBattlefieldDeparture(moved, permanent, graveyardId = null)
    // CR 506.4: an exiled permanent that was in combat is removed from it.
    return ExiledPermanent(clearCombatReferences(triggered, setOf(objectId)), exileId)
}

/**
 * The result of exiling a permanent: the successor [state] and the [exileId] the exiled card now has in
 * the exile zone (CR 400.7).
 *
 * @property state the state after the exile, its departure triggers, and its combat release.
 * @property exileId the new exile object's id — the only handle a later effect has on the exiled card,
 *   because the battlefield id it had before is gone with the object.
 */
internal data class ExiledPermanent(
    val state: GameState,
    val exileId: ObjectId,
)
