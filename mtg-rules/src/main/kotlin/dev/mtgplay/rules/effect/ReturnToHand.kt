package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.clearCombatReferences
import dev.mtgplay.rules.engine.emit
import dev.mtgplay.rules.engine.updateBattlefield
import dev.mtgplay.rules.engine.updatePlayer

/**
 * Effect primitive: returns the graveyard object [objectId] to its owner's hand (CR 400.7) — the
 * published building block a "return this to its owner's hand" effect composes (ADR-003; Rancor's
 * leaves-the-battlefield trigger is the first client).
 *
 * The object leaves its owner's graveyard for their hand as a **new** object (CR 400.7), emitting
 * [GameEvent.CardReturnedToHand]. **Honest last-known information (CR 603.10):** the [objectId] is the
 * fresh graveyard object the trigger captured when the card arrived there; if that object is no longer
 * in any graveyard — it has since moved and become a different object (CR 400.7) — the effect does
 * nothing, because the thing it was told to return no longer exists. In the MVP pool nothing removes
 * Rancor from the graveyard before its trigger resolves, so the return always succeeds.
 */
fun returnToOwnersHand(
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
    val (handId, allocated) = state.allocateObjectId()
    val reborn = GameObject(id = handId, card = leaving.card, owner = leaving.owner)
    return allocated
        .updatePlayer(owner) { it.copy(graveyard = it.graveyard.removingAt(index), hand = it.hand.adding(reborn)) }
        .emit(GameEvent.CardReturnedToHand(owner, handId, leaving.card))
}

/**
 * Effect primitive: returns the **battlefield permanent** [objectId] to its owner's hand (CR 400.7) — the
 * published building block a "return target &lt;permanent&gt; to its owner's hand" resolution composes
 * (ADR-003; Steel Sabotage's bounce mode is the first client, `FW-MODAL`).
 *
 * The battlefield sibling of [returnToOwnersHand], and a **separate function rather than a widened one**,
 * for the reason [destroy] and [exilePermanent] are separate from each other: they start in different
 * zones, and a permanent leaving the battlefield drags consequences a graveyard card has none of. Folding
 * the two together would mean one body whose behaviour depends on where it happened to find the object,
 * which is how a zone-change rule drifts.
 *
 * What leaving the battlefield costs, and where each part is handled:
 * - The permanent becomes a **new object** in its owner's hand (CR 400.7), carrying no battlefield
 *   memory — untapped, no marked damage, no counters, attached to nothing. That is the [GameObject]
 *   defaults, exactly as in [destroy] and [exilePermanent].
 * - **Combat releases it** (CR 506.4), through the same [clearCombatReferences] those two call, so a
 *   bounced attacker or blocker leaves combat as a destroyed one does.
 * - An **Aura on it falls off** at the next CR 704.5m state-based check, which is where that rule lives —
 *   not here, and not for this primitive to anticipate.
 * - **No graveyard trigger fires.** A "put into a graveyard from the battlefield" trigger (CR 603.6b)
 *   watches for the graveyard specifically, so a bounce is deliberately not routed through
 *   `detectPutIntoGraveyardTriggers`. A general "leaves the battlefield" trigger would be — and the
 *   engine has no such condition, which is why this is a note rather than a call.
 * - **Indestructible is not consulted** (CR 702.12b): it stops destruction, not a zone change. Bouncing
 *   an indestructible permanent works, and that is the rules answer rather than an oversight.
 *
 * Fails loudly if [objectId] is not on the battlefield: every caller reaches this after the CR 608.2b
 * re-check has confirmed its target is still a legal battlefield permanent (ADR-005), so a missing one is
 * an engine defect. Note the contrast with [returnToOwnersHand], whose graveyard object legitimately may
 * have moved on (it is captured as last-known information by a trigger, not re-checked as a target).
 */
fun returnPermanentToOwnersHand(
    state: GameState,
    objectId: ObjectId,
): GameState {
    val battlefield = state.sharedZones.battlefield
    val index = battlefield.indexOfFirst { it.id == objectId }
    require(index >= 0) { "CR 400.7: a bounced permanent must be on the battlefield, but $objectId is not" }
    val permanent = battlefield[index]
    val (handId, allocated) = state.allocateObjectId()
    val reborn = GameObject(id = handId, card = permanent.card, owner = permanent.owner)
    val moved =
        allocated
            .updateBattlefield { it.removingAt(index) }
            .updatePlayer(permanent.owner) { it.copy(hand = it.hand.adding(reborn)) }
            .emit(GameEvent.CardReturnedToHand(permanent.owner, handId, permanent.card))
    // CR 506.4: a bounced permanent that was in combat is removed from it.
    return clearCombatReferences(moved, setOf(objectId))
}
