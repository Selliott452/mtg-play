package dev.mtgplay.rules.engine

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState

/*
 * The consequence of the CR 704.5m aura-fall-off state-based action (detected in
 * StateBasedActions.kt): an Aura attached to an illegal object or to nothing leaves the battlefield
 * for its owner's graveyard. Detection is a pure read; this file performs the move.
 */

/**
 * Performs a batch of Aura fall-off state-based actions simultaneously (CR 704.3): each Aura leaves
 * the battlefield for its owner's graveyard as a **new** object (CR 400.7 — the fresh object carries
 * no [GameObject.attachedTo], exactly as marked damage and tapped are dropped on a zone move). Each
 * move emits [GameEvent.AuraFellOff].
 */
internal fun performAuraFallOffs(
    state: GameState,
    auras: List<ObjectId>,
): GameState = auras.fold(state, ::moveAuraToGraveyard)

// Moves the falling-off Aura [objectId] to its owner's graveyard as a new object (CR 704.5m put it
// there; CR 400.7 makes it a new object with no attachment). Fails loudly if it is not on the
// battlefield — the fall-off state-based action acts only on battlefield Auras.
private fun moveAuraToGraveyard(
    state: GameState,
    objectId: ObjectId,
): GameState {
    val battlefield = state.sharedZones.battlefield
    val index = battlefield.indexOfFirst { it.id == objectId }
    require(index >= 0) { "CR 704.5m: a falling-off Aura must be on the battlefield, but $objectId is not" }
    // CR 614.1a, CR 700.4: an Aura put into a graveyard from the battlefield dies like anything else, so
    // a delayed death replacement catches a Torched Aura that then falls off its dead creature.
    replaceBattlefieldDeath(state, objectId)?.let { return it }
    val aura = battlefield[index]
    val (graveyardId, allocated) = state.allocateObjectId()
    val reborn = GameObject(id = graveyardId, card = aura.card, owner = aura.owner)
    val moved =
        allocated
            .updateBattlefield { it.removingAt(index) }
            .updatePlayer(aura.owner) { it.copy(graveyard = it.graveyard.adding(reborn)) }
            .emit(GameEvent.AuraFellOff(objectId, aura.card, graveyardId))
    // CR 603.6b, CR 603.10: a "put into a graveyard from the battlefield" trigger (Rancor) fires now,
    // matched against the Aura's pre-departure state and carrying the fresh graveyard object it acts on.
    return announceBattlefieldDeparture(moved, aura, graveyardId)
}
