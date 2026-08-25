package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.announceBattlefieldEntry
import dev.mtgplay.rules.engine.updateBattlefield
import dev.mtgplay.rules.engine.updatePlayer

/**
 * Effect primitive: returns the graveyard object [objectId] to the battlefield **untapped**, under its
 * owner's control, as a **new** object (CR 400.7, CR 110.5a) — the published building block a "return
 * target creature card from your graveyard to the battlefield" resolution composes (ADR-003; Dread
 * Return is the first client). Additive, flagged (`W8-D`).
 *
 * **The sibling of [returnFromGraveyardToBattlefieldTapped], and a separate primitive rather than a
 * boolean on it.** The difference is not cosmetic and it is not the caller's to decide by flag: entering
 * *tapped* is a property the returning effect prints (Sneaky Snacker says so), while entering untapped
 * is the CR 110.5a default that applies when nothing says otherwise. A permanent returned by Dread
 * Return can attack the turn it arrives if it has haste, can block, and can pay a `{T}` cost — three
 * lines of play a tapped return deletes. The two functions state which rule each is, at their names.
 *
 * The object leaves its owner's graveyard for the battlefield as a fresh object (CR 400.7): summoning
 * sick (CR 302.6), untapped, and with no marked damage. Its own enters-the-battlefield triggers then
 * fire (CR 603.6a) — a reanimated Mulldrifter draws its two cards, and there is nothing about the
 * graveyard origin that suppresses them. Entry goes through [announceBattlefieldEntry], the single home
 * every entry path shares, so the triggers cannot be skipped the way the played-land path once skipped
 * them (triage T18).
 *
 * **Under its *owner's* control, which is the whole pool's model of control.** Every card in the
 * gauntlet that returns a creature card from a graveyard to the battlefield names *your* graveyard, so
 * owner and controller coincide and nothing here has to choose between them. A card returning an
 * opponent's creature card under your control would need a controller distinct from the owner on
 * [GameObject], which the engine does not have; such a card must not be encoded against this primitive.
 *
 * **Honest last-known information (CR 608.2b, CR 400.7):** [objectId] is the graveyard object the
 * resolving effect targeted; if it is no longer in any graveyard — it has since moved and become a
 * different object — the effect does nothing, which is the same answer [returnToOwnersHand] and
 * [exileCardFromGraveyard] give for the same reason.
 */
fun returnFromGraveyardToBattlefield(
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
    val reborn = GameObject(id = battlefieldId, card = leaving.card, owner = leaving.owner)
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
