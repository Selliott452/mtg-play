package dev.mtgplay.rules.engine

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState

/*
 * The seed of the general zone-move operation. Every move follows the same CR 400.7 shape —
 * remove the object from its old zone, allocate a fresh ObjectId (the object becomes a new
 * object with no memory of its former self), add it to the new zone per that zone's ordering
 * convention, emit an event. P1.2 needs exactly two moves, draw and discard; later packets
 * generalize this file rather than inventing a second pathway.
 */

/**
 * Draws a card (CR 504.1's and CR 103.5's operation): the top card of [player]'s library is put
 * into their hand as a new object (CR 400.7), emitting [GameEvent.CardDrawn].
 *
 * If the library is empty the draw **fails**: nothing moves, and the attempt is recorded on
 * [dev.mtgplay.core.state.PlayerState.attemptedDrawFromEmptyLibrary] as an explicit fact — the
 * CR 704.5c state-based action acts on that fact at the next check, never on library emptiness
 * itself.
 */
internal fun drawCard(
    state: GameState,
    player: PlayerId,
): GameState {
    val top =
        state.player(player).library.firstOrNull()
            ?: return state.updatePlayer(player) { it.copy(attemptedDrawFromEmptyLibrary = true) }
    val (id, allocated) = state.allocateObjectId()
    val drawn = top.copy(id = id)
    return allocated
        .updatePlayer(player) { it.copy(library = it.library.removingAt(0), hand = it.hand.adding(drawn)) }
        .emit(GameEvent.CardDrawn(player, id, drawn.card))
}

/**
 * Discards a card (CR 514.1's operation in P1.2): the hand object [objectId] of [player] is put
 * on top of their graveyard — the last position (CR 404) — as a new object (CR 400.7), emitting
 * [GameEvent.CardDiscarded]. Fails loudly if [objectId] is not in [player]'s hand.
 */
internal fun discardCard(
    state: GameState,
    player: PlayerId,
    objectId: ObjectId,
): GameState {
    val hand = state.player(player).hand
    val index = hand.indexOfFirst { it.id == objectId }
    require(index >= 0) { "object $objectId is not in player $player's hand" }
    val (id, allocated) = state.allocateObjectId()
    val discarded = hand[index].copy(id = id)
    return allocated
        .updatePlayer(player) { it.copy(hand = it.hand.removingAt(index), graveyard = it.graveyard.adding(discarded)) }
        .emit(GameEvent.CardDiscarded(player, id, discarded.card))
}
