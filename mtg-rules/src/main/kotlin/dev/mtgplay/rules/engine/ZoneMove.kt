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
 * A **successful** draw increments [player]'s per-turn draw count
 * ([dev.mtgplay.core.state.PlayerState.drawsThisTurn], reset each turn by [beginTurn]) and then fires
 * any "when you draw your Nth card in a turn" triggers whose threshold this draw crossed (CR 603.2,
 * [detectDrawCountTriggers]) — Sneaky Snacker's graveyard trigger. A failed draw counts nothing (no
 * card was drawn).
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
    val moved =
        allocated
            .updatePlayer(player) {
                it.copy(
                    library = it.library.removingAt(0),
                    hand = it.hand.adding(drawn),
                    drawsThisTurn = it.drawsThisTurn + 1,
                )
            }.emit(GameEvent.CardDrawn(player, id, drawn.card))
    // CR 603.2: a per-turn draw trigger fires the instant its ordinal is reached, never on later draws.
    return detectDrawCountTriggers(moved, player)
}

/**
 * Mills a card (CR 701.13a): the top card of [player]'s library is put into their graveyard as a new
 * object (CR 400.7), emitting [GameEvent.CardMilled].
 *
 * With an **empty** library nothing is milled (CR 701.13b: "mill as many as possible") — the state is
 * returned unchanged, and no draw-from-empty-library attempt is recorded, because milling is not
 * drawing (CR 121.1) and never causes the CR 704.5c loss. Milling is likewise not discarding: it emits
 * its own event and never routes through the CR 614/616 discard replacements, so a madness card milled
 * from the library goes to the graveyard like any other card (CR 702.35a replaces a *discard*).
 */
internal fun millCard(
    state: GameState,
    player: PlayerId,
): GameState {
    val top = state.player(player).library.firstOrNull() ?: return state
    val (id, allocated) = state.allocateObjectId()
    val milled = top.copy(id = id)
    return allocated
        .updatePlayer(player) {
            it.copy(library = it.library.removingAt(0), graveyard = it.graveyard.adding(milled))
        }.emit(GameEvent.CardMilled(player, id, milled.card))
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
