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
 * Effect primitive: **exiles [player]'s whole graveyard** (CR 701.3a, CR 404) — "Exile target player's
 * graveyard", the published building block Bojuka Bog's enters-the-battlefield trigger composes (ADR-003).
 * Additive, flagged (`W7-C`).
 *
 * **A separate primitive from [exileCardFromGraveyard], not a loop a caller writes.** The two differ in the
 * thing that matters about a zone-wide effect: *what it names*. A card exile names one object chosen as a
 * target (CR 115) and can therefore find it gone (CR 400.7) and legitimately do nothing; a graveyard exile
 * names a **player** and applies to whatever is in that player's graveyard at the moment it resolves — there
 * is no per-card target, so there is no per-card fizzle and no stale id to reconcile. Writing it as a fold
 * at each call site would put that distinction in the callers rather than in one place, and would also fix
 * the *order* by accident; here it is stated: bottom-up, so the relative order of the exile zone matches the
 * order the cards were in the graveyard.
 *
 * An **empty graveyard is not an error** and not a special case: the fold does nothing, no event is emitted,
 * and the state is returned unchanged. "Exile target player's graveyard" is legal against an empty one — the
 * target is the player, who is always a legal target (CR 115.1a) — so a no-op here is the rules answer, not a
 * swallowed failure.
 *
 * Each card leaves for the shared exile zone as a **new** object (CR 400.7), emitting its own
 * [GameEvent.GraveyardCardExiled]: one event per card rather than one per zone, so a transcript records
 * exactly which cards a graveyard exile took, and a card exiled this way is indistinguishable in the log
 * from one exiled singly.
 */
fun exileGraveyard(
    state: GameState,
    player: PlayerId,
): GameState =
    state.players
        .getValue(player)
        .graveyard
        .map { it.id }
        .fold(state) { current, id -> exileCardFromGraveyard(current, id) }
