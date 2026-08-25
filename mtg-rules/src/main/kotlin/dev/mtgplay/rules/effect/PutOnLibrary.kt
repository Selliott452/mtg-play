package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.emit
import dev.mtgplay.rules.engine.updatePlayer

/**
 * Effect primitive: puts the graveyard card [objectId] **on top of its owner's library** (CR 400.7,
 * CR 401.1) — the published building block a "put target card from your graveyard on top of your
 * library" resolution composes (ADR-003; Mortuary Mire's enters-the-battlefield trigger is the first
 * client). Additive, flagged (`W8-A`).
 *
 * **The third graveyard-exit primitive, and a separate one for the reason the other two are separate.**
 * [exileCardFromGraveyard] moves a card to exile and [shuffleIntoOwnersLibrary] moves it into a library
 * and randomises it; this one moves it into a library at a **known position**, which is the whole point
 * of the effect — the controller's next draw is now a card they chose. Parameterising one function by
 * destination would put three genuinely different outcomes behind one name, and the difference between
 * "somewhere in your library" and "the card you draw next turn" is the largest of the three.
 *
 * **Owner, not controller** (CR 108.3): the card goes into the library of the player whose graveyard it
 * is in, which a graveyard already names (CR 404.1 — a graveyard holds only its owner's cards).
 *
 * **Honest last-known information (CR 603.10, CR 400.7):** [objectId] is the graveyard object the
 * ability targeted at CR 603.3d. If it is no longer in any graveyard — something moved it in response,
 * so it is a different object now — the effect does nothing, which is the shape [returnToOwnersHand],
 * [exileCardFromGraveyard] and [shuffleIntoOwnersLibrary] all take. It is reachable here rather than
 * theoretical: the CR 608.2b re-check runs before the resolution begins, and Mortuary Mire's own "you
 * may" pause sits between that check and this move.
 *
 * The card changes zones, so it is reborn with a fresh object id (CR 400.7) and narrated with
 * [GameEvent.CardPutOnLibrary] — the same event a library look emits for a card it places, and marked
 * `onTop` for the same reason.
 */
fun putGraveyardCardOnTopOfOwnersLibrary(
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
    val (libraryId, allocated) = state.allocateObjectId()
    val reborn = GameObject(id = libraryId, card = leaving.card, owner = leaving.owner)
    return allocated
        .updatePlayer(owner) {
            // CR 401.1: index 0 is the top of a library.
            it.copy(graveyard = it.graveyard.removingAt(index), library = it.library.addingAt(0, reborn))
        }.emit(GameEvent.CardPutOnLibrary(owner, libraryId, leaving.card, onTop = true))
}
