package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.random.shuffled
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.emit
import dev.mtgplay.rules.engine.player
import dev.mtgplay.rules.engine.updatePlayer

/**
 * Effect primitive: shuffles the graveyard object [objectId] into its owner's library (CR 400.7,
 * CR 701.20) — the published building block a "its owner shuffles it into their library" effect
 * composes (ADR-003; Lembas' leaves-the-battlefield trigger is the first client, `FW-SHUFFLEIN`).
 *
 * **The card and the shuffle are one operation, in that order.** The card is put into the library as a
 * **new** object (CR 400.7) and the whole library is then randomised (CR 701.20), so the card's
 * position is unknown to everyone — including the player whose library it is. Adding it and shuffling
 * separately would be the same states in the same order, but writing it as one primitive is what stops
 * a caller from doing the first half alone and quietly putting a known card on the bottom.
 *
 * The randomisation draws from the **match-owned** [dev.mtgplay.core.random.Rng] and returns its
 * successor on the state (ADR-006): the shuffle consumes seeded entropy, so a replay of the same seed
 * reproduces the same library order. There is no other sanctioned source of randomness.
 *
 * **Honest last-known information (CR 603.10):** [objectId] is the fresh graveyard object the trigger
 * captured when the card arrived there; if it is no longer in any graveyard — it has since moved and
 * become a different object (CR 400.7) — the effect does nothing, because the thing it was told to
 * shuffle in no longer exists. That is the shape [returnToOwnersHand] and
 * [returnFromGraveyardToBattlefieldTapped] already take, and it matters more here than for either:
 * Lembas is a *recursive* card, so a second Lembas trigger resolving after the first has already moved
 * the card must find nothing rather than duplicate it.
 *
 * **Owner, not controller.** The card goes into its *owner's* library (CR 108.3, and Lembas says
 * "its owner"), which the graveyard it sits in already names — a graveyard holds only its owner's cards
 * (CR 404.1).
 */
fun shuffleIntoOwnersLibrary(
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
    val inLibrary =
        allocated.updatePlayer(owner) {
            it.copy(graveyard = it.graveyard.removingAt(index), library = it.library.adding(reborn))
        }
    // CR 701.20, ADR-006: the randomisation is what hides the card, and it draws from the match PRNG.
    val (shuffled, nextRng) = inLibrary.player(owner).library.shuffled(inLibrary.rng)
    return inLibrary
        .copy(rng = nextRng)
        .updatePlayer(owner) { it.copy(library = shuffled) }
        .emit(GameEvent.CardShuffledIntoLibrary(owner, libraryId, leaving.card))
}
