package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.emit
import dev.mtgplay.rules.engine.updateExile
import dev.mtgplay.rules.engine.updatePlayer

/**
 * Effect primitive: **exiles the graveyard card** [objectId] (CR 701.3a) — the published building block
 * an "exile target card from a graveyard" resolution composes (ADR-003; Faerie Macabre is the first
 * client). Additive, flagged (`FW-MULTITGT`).
 *
 * The card leaves its owner's graveyard for the shared exile zone as a **new** object (CR 400.7),
 * emitting [GameEvent.GraveyardCardExiled]. It is face up and carries no marker for a later return:
 * nothing in the gauntlet brings a card back from exile except by a permission recorded elsewhere.
 *
 * **The sibling of [exilePermanent], and separate on purpose.** That one moves an object off the
 * *battlefield*, which means releasing it from combat (CR 506.4) and leaving any Aura on it to fall off
 * at the next CR 704.5m check; none of that has any meaning for a card in a graveyard. Parameterising
 * one function by zone would put two genuinely different move sequences behind one name, which is the
 * shape `Exile.kt` already refused when it declined to fold the graveyard-cost exile in.
 *
 * **Missing means gone, and that is honest last-known information** (CR 400.7, CR 603.10). Unlike
 * [exilePermanent], this primitive does *not* fail on an absent object: a multi-target effect exiling
 * two cards moves the first before it looks for the second, and a *single* resolution can therefore
 * legitimately arrive at a card that has already left — the CR 608.2b re-check runs once, before the
 * effect begins, not between its steps. Returning the state unchanged is the same answer
 * [returnToOwnersHand] gives for the same reason. What the re-check does guarantee is that at least one
 * chosen target was legal when the object began resolving; a target that has since gone simply does
 * nothing.
 */
fun exileCardFromGraveyard(
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
    val (exileId, allocated) = state.allocateObjectId()
    val reborn = GameObject(id = exileId, card = leaving.card, owner = leaving.owner)
    return allocated
        .updatePlayer(owner) { it.copy(graveyard = it.graveyard.removingAt(index)) }
        .updateExile { it.adding(reborn) }
        .emit(GameEvent.GraveyardCardExiled(owner, objectId, leaving.card, exileId))
}

/**
 * Effect primitive: **exiles every card in [owner]'s graveyard** (CR 701.3a, CR 404) — the published
 * building block Thraben Charm's "Exile any number of target players' graveyards" composes (ADR-003;
 * it is the first and only client). Additive, flagged (`FW-MULTITGT`'s second wave).
 *
 * The whole-zone sibling of [exileCardFromGraveyard], and a separate primitive rather than a fold left
 * to the card, because "exile a player's graveyard" is a distinct printed instruction and the card
 * definition should name it rather than re-derive it (ADR-003 vocabulary discipline). Each card moves
 * individually, so every consequence [exileCardFromGraveyard] documents holds per card: a new object
 * with a fresh id (CR 400.7) and a [GameEvent.GraveyardCardExiled] apiece.
 *
 * **The graveyard is read once, up front.** The ids are collected before any card moves, so the fold
 * cannot be confused by the zone shrinking under it — the alternative, looping until the graveyard is
 * empty, would be a different program the moment anything ever replaced a graveyard exile with a
 * different move.
 *
 * **Order is the graveyard's own** (CR 404.1 makes a graveyard an ordered zone). Nothing in the
 * gauntlet observes it — no card counts what was exiled or in what sequence — but exiling in an
 * arbitrary or seeded-random order would be a hidden nondeterminism (ADR-006), so the zone's own order
 * is used and stated.
 *
 * **An empty graveyard is a correct input**: the state comes back unchanged, with no events. "Any
 * number of target players' graveyards" may legally name a player whose graveyard is empty, and that
 * choice simply does nothing rather than failing.
 */
fun exileGraveyard(
    state: GameState,
    owner: PlayerId,
): GameState =
    state.players
        .getValue(owner)
        .graveyard
        .map { it.id }
        .fold(state, ::exileCardFromGraveyard)
