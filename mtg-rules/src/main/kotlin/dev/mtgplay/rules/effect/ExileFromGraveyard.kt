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
 * building block Thraben Charm's "Exile any number of target players' graveyards" and Bojuka Bog's
 * enters-the-battlefield trigger both compose (ADR-003). Additive, flagged (`FW-MULTITGT`'s second wave,
 * and reached again by `W7-C`).
 *
 * The whole-zone sibling of [exileCardFromGraveyard], and a separate primitive rather than a fold left
 * to the card, because the two differ in the thing that matters about a zone-wide effect: *what it
 * names*. A card exile names one object chosen as a target (CR 115) and can therefore find it gone
 * (CR 400.7) and legitimately do nothing; a graveyard exile names a **player** and applies to whatever
 * is in that player's graveyard when it resolves — no per-card target, so no per-card fizzle and no
 * stale id to reconcile. Writing the fold at each call site would scatter that distinction across
 * callers and would fix the order by accident rather than by decision.
 *
 * **The graveyard is read once, up front.** The ids are collected before any card moves, so the fold
 * cannot be confused by the zone shrinking under it — the alternative, looping until the graveyard is
 * empty, would be a different program the moment anything ever replaced a graveyard exile with a
 * different move.
 *
 * **Order is the graveyard's own** (CR 404.1 makes a graveyard an ordered zone), bottom-up, so the
 * relative order of the exile zone matches the order the cards were in the graveyard. Nothing in the
 * gauntlet observes it — no card counts what was exiled or in what sequence — but exiling in an
 * arbitrary or seeded-random order would be a hidden nondeterminism (ADR-006), so the zone's own order
 * is used and stated.
 *
 * **An empty graveyard is a correct input**, not a special case: the state comes back unchanged, with
 * no events. "Any number of target players' graveyards" may legally name a player whose graveyard is
 * empty, and "exile target player's graveyard" is legal against an empty one — the target is the
 * player, who is always a legal target (CR 115.1a). A no-op here is the rules answer, not a swallowed
 * failure.
 *
 * Each card leaves for the shared exile zone as a **new** object (CR 400.7), emitting its own
 * [GameEvent.GraveyardCardExiled]: one event per card rather than one per zone, so a transcript records
 * exactly which cards a graveyard exile took, and a card exiled this way is indistinguishable in the
 * log from one exiled singly.
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
