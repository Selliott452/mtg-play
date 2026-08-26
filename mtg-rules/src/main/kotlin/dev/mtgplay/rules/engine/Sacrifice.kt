package dev.mtgplay.rules.engine

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState

/*
 * Sacrifice as a cost (CR 701.17, CR 601.2h): the non-mana cost component of Fireblast's alternative
 * cost and Lava Dart's flashback. A sacrificed permanent leaves the battlefield for its owner's
 * graveyard as a new object (CR 400.7); any leaves-the-battlefield trigger fires and combat lets the
 * permanent go, reusing the same seams a death SBA does. The MVP's sacrifice fodder is Mountains
 * (no trigger, never in combat), but the general path is honest.
 */

/**
 * Sacrifices the battlefield permanents [objectIds] to pay a cost (CR 701.17): each moves to its
 * owner's graveyard as a new object (CR 400.7), a "put into a graveyard from the battlefield" trigger
 * (CR 603.6b) is detected against its pre-sacrifice state, and combat releases it (CR 506.4). Emits
 * [GameEvent.PermanentSacrificed] per permanent. The permanents were chosen legally while gathering
 * (ADR-005), so a missing one is an engine defect and fails loudly. A no-op for an empty list.
 */
internal fun sacrificePermanents(
    state: GameState,
    player: PlayerId,
    objectIds: List<ObjectId>,
): GameState {
    if (objectIds.isEmpty()) return state
    val sacrificed =
        objectIds.fold(state) { current, id -> sacrificeOnePermanent(current, player, id) }
    // CR 506.4: a sacrificed permanent that was in combat is removed from it.
    return clearCombatReferences(sacrificed, objectIds.toSet())
}

/**
 * Sacrifices the battlefield permanent [objectId] under its own controller's control (CR 701.17a,
 * CR 701.17b) — the *effect*-side entry point [dev.mtgplay.rules.effect.sacrificePermanent] publishes,
 * as distinct from [sacrificePermanents]' cost-side one.
 *
 * A permanent that is no longer on the battlefield is a **no-op**: an effect that says "sacrifice it"
 * legitimately resolves after its subject has gone (CR 603.10), unlike a cost, which was checked before
 * it was ever offered. Everything else — the CR 400.7 move, the CR 603.6b trigger, the CR 506.4 combat
 * release — is [sacrificeOnePermanent]'s, unchanged, so the two paths cannot drift.
 */
internal fun sacrificeControlledPermanent(
    state: GameState,
    objectId: ObjectId,
): GameState {
    val permanent = state.sharedZones.battlefield.firstOrNull { it.id == objectId } ?: return state
    // CR 701.17b: only the permanent's own controller may sacrifice it; control is ownership here.
    val sacrificed = sacrificeOnePermanent(state, permanent.owner, objectId)
    return clearCombatReferences(sacrificed, setOf(objectId))
}

private fun sacrificeOnePermanent(
    state: GameState,
    player: PlayerId,
    objectId: ObjectId,
): GameState {
    val battlefield = state.sharedZones.battlefield
    val index = battlefield.indexOfFirst { it.id == objectId }
    require(index >= 0) { "CR 701.17: a sacrificed permanent must be on the battlefield, but $objectId is not" }
    val permanent = battlefield[index]
    require(permanent.owner == player) {
        "CR 701.17: $player may sacrifice only a permanent they control, but $objectId is ${permanent.owner}'s"
    }
    // CR 603.6c, CR 603.10a: "whenever you sacrifice another <subtype>" looks back in time, so it is
    // detected here — against the state in which the permanent is still on the battlefield and its
    // layer-4 subtypes are still readable — and *before* the death replacement below, because CR 701.17a's
    // event is the sacrifice itself rather than the arrival in a graveyard.
    val watched = detectSacrificeTriggers(state, player, objectId)
    // CR 614.1a, CR 700.4: a sacrifice puts a permanent into a graveyard from the battlefield, so it is a
    // death, and a delayed replacement may exile it instead. The cost was still paid — CR 701.17a's
    // requirement is that the permanent be sacrificed, not that it reach a graveyard.
    replaceBattlefieldDeath(watched, objectId)?.let { return it }
    // CR 608.2h: the layered power this permanent leaves with, for a reader that resolves after it
    // is gone (Monstrous Emergence). Captured before the removal — it cannot be computed after.
    val (graveyardId, allocated) = rememberLastKnownPower(watched, objectId).allocateObjectId()
    val reborn = GameObject(id = graveyardId, card = permanent.card, owner = permanent.owner)
    val moved =
        allocated
            .updateBattlefield { it.removingAt(index) }
            .updatePlayer(permanent.owner) { it.copy(graveyard = it.graveyard.adding(reborn)) }
            .emit(GameEvent.PermanentSacrificed(player, objectId, permanent.card, graveyardId))
    // CR 603.6b, CR 603.10: a leaves-the-battlefield trigger fires against the pre-sacrifice state.
    return announceBattlefieldDeparture(moved, permanent, graveyardId)
}
