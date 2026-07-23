package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.emit
import dev.mtgplay.rules.engine.updatePlayer

/**
 * Effect primitive: returns the graveyard object [objectId] to its owner's hand (CR 400.7) — the
 * published building block a "return this to its owner's hand" effect composes (ADR-003; Rancor's
 * leaves-the-battlefield trigger is the first client).
 *
 * The object leaves its owner's graveyard for their hand as a **new** object (CR 400.7), emitting
 * [GameEvent.CardReturnedToHand]. **Honest last-known information (CR 603.10):** the [objectId] is the
 * fresh graveyard object the trigger captured when the card arrived there; if that object is no longer
 * in any graveyard — it has since moved and become a different object (CR 400.7) — the effect does
 * nothing, because the thing it was told to return no longer exists. In the MVP pool nothing removes
 * Rancor from the graveyard before its trigger resolves, so the return always succeeds.
 */
fun returnToOwnersHand(
    state: GameState,
    objectId: ObjectId,
): GameState {
    val owner =
        state.players.keys
            .firstOrNull { seat ->
                state.players
                    .getValue(seat)
                    .graveyard
                    .any { it.id == objectId }
            }
            ?: return state
    val graveyard = state.players.getValue(owner).graveyard
    val index = graveyard.indexOfFirst { it.id == objectId }
    val leaving = graveyard[index]
    val (handId, allocated) = state.allocateObjectId()
    val reborn = GameObject(id = handId, card = leaving.card, owner = leaving.owner)
    return allocated
        .updatePlayer(owner) { it.copy(graveyard = it.graveyard.removingAt(index), hand = it.hand.adding(reborn)) }
        .emit(GameEvent.CardReturnedToHand(owner, handId, leaving.card))
}
