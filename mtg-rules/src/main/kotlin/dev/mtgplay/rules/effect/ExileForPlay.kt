package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.emit
import dev.mtgplay.rules.engine.updateExile
import dev.mtgplay.rules.engine.updatePlayer

/**
 * Effect primitive: **exiles the top [count] cards of [player]'s library and grants [player]
 * permission to play them until the end of their next turn** (CR 701.3a, CR 118.5, CR 601.2a) — the
 * published building block Reckless Impulse's *"Exile the top two cards of your library. Until the end
 * of your next turn, you may play those cards"* composes (ADR-003). Additive, flagged (`W8-D`).
 *
 * **This is not a [dev.mtgplay.core.definition.CastingPermission], and it cannot be one.** Every member
 * of that type is a permission a *card declares about itself* — flashback, evoke, plot — read off the
 * definition of the card being cast. This permission is granted by a **different** object's resolution
 * to whatever cards happened to be on top of a library, so the card being played knows nothing about it
 * and there is no declaration to read. It therefore lives on the exiled **object** as
 * [GameObject.playGrantedTurn], and `mtg-rules` enumerates it from the exile zone alongside the
 * permission casts.
 *
 * **"Play", not "cast" (CR 601.2a, CR 116.2a).** The permission covers a land as well as a spell, and
 * the two reach the battlefield by different routes — a land is *played* as a special action and is
 * never cast (CR 305.1). Both are enumerated; encoding only the cast half would silently delete every
 * land off the top, which on a two-card exile is most of them.
 *
 * **The cards are played at their normal cost.** Nothing about this permission is an alternative cost
 * (CR 118.9): it changes *where* a card may be played from, not what it costs, so a `{4}{U}` card
 * exiled this way still costs `{4}{U}`. That is the other reason it is not a `CastingPermission`, whose
 * whole contract is a replacement cost.
 *
 * **The duration is recorded as its start, not its end** — see [GameObject.playGrantedTurn]. The engine
 * ends the permission at the CR 514.2 cleanup of the first turn that is the owner's and is later than
 * the grant, which is "the end of your next turn" without predicting the turn order.
 *
 * Each card leaves the library for the shared exile zone as a **new** object (CR 400.7), face up,
 * emitting [GameEvent.CardsExiledFromLibrary]. A library shorter than [count] exiles what it has, which
 * is a correct input rather than a special case; an empty one exiles nothing and emits nothing.
 */
fun exileTopCardsPlayableUntilEndOfYourNextTurn(
    state: GameState,
    player: PlayerId,
    count: Int,
): GameState {
    require(count >= 1) { "CR 701.3a: this effect exiles at least one card, was $count" }
    val exiled =
        state.players
            .getValue(player)
            .library
            .take(count)
    if (exiled.isEmpty()) return state
    val moved =
        exiled.fold(state) { current, libraryObject ->
            val (exileId, allocated) = current.allocateObjectId()
            val reborn =
                GameObject(
                    id = exileId,
                    card = libraryObject.card,
                    owner = libraryObject.owner,
                    // CR 118.5: the permission rides on the object, recorded by the turn it began.
                    playGrantedTurn = allocated.turn.number,
                )
            allocated
                .updatePlayer(player) { it.copy(library = it.library.removingAt(0)) }
                .updateExile { it.adding(reborn) }
        }
    return moved.emit(GameEvent.CardsExiledFromLibrary(player, exiled.map { it.card }))
}
