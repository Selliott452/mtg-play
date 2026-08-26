package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.TokenDefinition
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState

/*
 * The consequence of the CR 704.5d token-cessation state-based action (detected in
 * StateBasedActions.kt): a token in any zone other than the battlefield ceases to exist. Detection is
 * a pure read; this file performs the removal — the token leaves its zone and is gone entirely, put
 * nowhere (unlike a card, a token is not conserved).
 */

/**
 * Whether the object [obj] is a token (CR 111): its printed reference resolves to a [TokenDefinition]
 * in the state's definition registry. Stable across the CR 400.7 rebirths, since the printed reference
 * is conserved — a token reborn in the graveyard is still recognizably a token. A definitionless object
 * is never a token.
 */
internal fun isToken(
    state: GameState,
    obj: GameObject,
): Boolean = state.definitions[obj.card] is TokenDefinition

/**
 * Performs a batch of CR 704.5d token-cessation state-based actions simultaneously (CR 704.3): each
 * token is removed from its owner's graveyard and ceases to exist — it is put nowhere, because a token
 * is not a card and is not conserved. Each removal emits [GameEvent.TokenCeasedToExist]. In the MVP
 * pool a ceasing token is always in a graveyard (see [removeCeasedToken]).
 */
internal fun performTokenCeasesToExist(
    state: GameState,
    tokens: List<ObjectId>,
): GameState = tokens.fold(state, ::removeCeasedToken)

/**
 * Removes the ceasing token [objectId] from a graveyard or from exile and emits the cessation event.
 *
 * **Two reachable zones, not one.** A token creature that dies (CR 704.5f/g) is in its owner's graveyard
 * for the moment between two checks; a token *exiled* from the battlefield is in the shared exile zone
 * for the same moment, and the pool reaches that three ways — an "exile target creature" removal
 * (Ride's End, Last Breath, Scour from Existence) pointed at a token, and, since `W9-D`, a delayed
 * death replacement that exiles a token instead of letting it die (Torch the Tower). Both are ordinary
 * lines, and both end here.
 *
 * A library, a hand, and the stack stay an unimplemented corner and fail loudly rather than guess
 * (CONVENTIONS.md loud-failure rule): no card in the gauntlet puts a token into any of them, and the
 * general cessation arrives with the first one that does.
 */
private fun removeCeasedToken(
    state: GameState,
    objectId: ObjectId,
): GameState {
    val owner =
        state.players.entries
            .firstOrNull { (_, playerState) -> playerState.graveyard.any { it.id == objectId } }
            ?.key
    if (owner != null) {
        val graveyard = state.players.getValue(owner).graveyard
        val index = graveyard.indexOfFirst { it.id == objectId }
        return state
            .updatePlayer(owner) { it.copy(graveyard = it.graveyard.removingAt(index)) }
            .emit(GameEvent.TokenCeasedToExist(objectId, graveyard[index].card))
    }
    val exileIndex = state.sharedZones.exile.indexOfFirst { it.id == objectId }
    require(exileIndex >= 0) {
        "CR 704.5d: a ceasing token is in a graveyard or in exile in this pool; $objectId is in neither " +
            "(library/hand/stack token cessation arrives with a card that puts one there)"
    }
    val exiled = state.sharedZones.exile[exileIndex]
    return state
        .updateExile { it.removingAt(exileIndex) }
        .emit(GameEvent.TokenCeasedToExist(objectId, exiled.card))
}
