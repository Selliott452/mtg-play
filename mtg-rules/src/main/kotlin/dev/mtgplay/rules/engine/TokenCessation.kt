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
 * Removes the ceasing token [objectId] from its owner's graveyard and emits the cessation event.
 *
 * In the MVP pool the only reachable off-battlefield zone for a token is a graveyard — a token creature
 * dies (CR 704.5f/g) into its owner's graveyard, then ceases here on the following check. No MVP card
 * puts a token into a library, hand, exile, or the stack, so those zones are an unimplemented corner
 * and this fails loudly rather than guess (CONVENTIONS.md loud-failure rule); the general cessation
 * arrives with the first card that bounces or exiles a token.
 */
private fun removeCeasedToken(
    state: GameState,
    objectId: ObjectId,
): GameState {
    val owner =
        state.players.entries
            .firstOrNull { (_, playerState) -> playerState.graveyard.any { it.id == objectId } }
            ?.key
            ?: error(
                "CR 704.5d: the only reachable off-battlefield zone for a token in the MVP pool is a graveyard; " +
                    "$objectId is in none (library/hand/exile token cessation arrives with a card that puts one there)",
            )
    val graveyard = state.players.getValue(owner).graveyard
    val index = graveyard.indexOfFirst { it.id == objectId }
    return state
        .updatePlayer(owner) { it.copy(graveyard = it.graveyard.removingAt(index)) }
        .emit(GameEvent.TokenCeasedToExist(objectId, graveyard[index].card))
}
