package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.announceBattlefieldEntry
import dev.mtgplay.rules.engine.updateBattlefield
import dev.mtgplay.rules.engine.updatePlayer

/**
 * Effect primitive: returns the graveyard object [objectId] to the battlefield tapped, under its
 * owner's control, as a **new** object (CR 400.7, CR 110.5b) — the published building block a
 * "return this card from your graveyard to the battlefield tapped" effect composes (ADR-003; Sneaky
 * Snacker's per-turn draw trigger is the first client).
 *
 * The object leaves its owner's graveyard for the battlefield as a fresh object (CR 400.7): summoning
 * sick (CR 302.6), **tapped** (the effect says so, overriding the CR 110.5a untapped default), and
 * with no marked damage. Its own enters-the-battlefield triggers then fire (CR 603.6a); Sneaky
 * Snacker has none. Emits [GameEvent.PermanentEntered] as any permanent entry does — the object
 * entered the battlefield, not a spell resolution's graveyard move. Both halves go through
 * [announceBattlefieldEntry], the single home every entry path shares.
 *
 * **Honest last-known information (CR 603.10):** [objectId] is the fresh graveyard object the trigger
 * captured when the card arrived there; if it is no longer in any graveyard — it has since moved and
 * become a different object (CR 400.7) — the effect does nothing, because the thing it was told to
 * return no longer exists.
 */
fun returnFromGraveyardToBattlefieldTapped(
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
    val (battlefieldId, allocated) = state.allocateObjectId()
    val reborn = GameObject(id = battlefieldId, card = leaving.card, owner = leaving.owner, tapped = true)
    val onBattlefield =
        allocated
            .updatePlayer(owner) { it.copy(graveyard = it.graveyard.removingAt(index)) }
            .updateBattlefield { it.adding(reborn) }
    return announceBattlefieldEntry(
        onBattlefield,
        battlefieldId,
        GameEvent.PermanentEntered(owner, objectId, leaving.card, battlefieldId),
    )
}
