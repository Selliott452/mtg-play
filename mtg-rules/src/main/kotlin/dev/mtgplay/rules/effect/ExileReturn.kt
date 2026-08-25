package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.announceBattlefieldEntry
import dev.mtgplay.rules.engine.emit
import dev.mtgplay.rules.engine.updateBattlefield
import dev.mtgplay.rules.engine.updateExile
import dev.mtgplay.rules.engine.updatePlayer

/*
 * Returning a card from exile (CR 400.7) — the second half of every exile-and-return card, and the
 * half the engine had no primitive for at all before `FW-BLINK`.
 *
 * Three rules govern all of it and they are worth stating once here rather than three times below.
 *
 * **CR 400.7 — the returning card is a new object.** It gets a fresh id, and it carries nothing from
 * either of its previous residences: not the counters it had on the battlefield (CR 122.2), not the
 * damage marked on it, not its tapped status, not the Aura it wore, not the linked-exile record it kept
 * while it was a Journey to Nowhere. This is why a "blink" answers an opposing Aura, resets a creature's
 * damage, and re-fires its own enters-the-battlefield ability — all of it falls out of building a fresh
 * [GameObject] rather than copying one, which is what every other zone move in the engine already does.
 *
 * **CR 603.6a — the return re-fires enters-the-battlefield triggers**, and it does so by going through
 * [announceBattlefieldEntry], the single home every entry path shares. That is the whole reason blink is
 * a value engine in Pauper, and it is deliberately *not* implemented as a fifth entry path with its own
 * remembered call to the detector: T18 is the record of what happens when an entry path forgets.
 *
 * **CR 603.10 — honest last-known information.** Every function here takes the id an ability captured
 * when it exiled the card, and every one of them does **nothing** if that id is no longer in exile. A
 * card that has already left exile has become a different object; the thing the ability was told to
 * return does not exist, and the CR-correct answer is to return nothing rather than to guess which card
 * was meant.
 */

/**
 * Effect primitive: returns the exile object [exileId] to the battlefield under its **owner's** control
 * as a new object (CR 400.7, CR 110.5b) — the published building block an exile-and-return effect
 * composes (ADR-003; Journey to Nowhere's leaves-the-battlefield trigger and Ephemerate are the first
 * clients).
 *
 * It enters **untapped** (CR 110.5a's default, which nothing here overrides) and summoning sick
 * (CR 302.6), and its own enters-the-battlefield triggers fire (CR 603.6a). Emits
 * [GameEvent.PermanentEntered] as any permanent entry does.
 *
 * **"Under its owner's control" is not a decoration.** Both printed cards say it — Ephemerate's "return
 * it to the battlefield under its owner's control" and Journey to Nowhere's "return the exiled card to
 * the battlefield under its owner's control" — and it is the reason a Journey to Nowhere cast on an
 * opponent's creature gives that creature *back to the opponent* when the Journey dies, rather than
 * handing it to whoever removed the Journey. Control is ownership in the current pool, so the two
 * coincide today; the line is written against [GameObject.owner] rather than against a controller so
 * that it stays right on the day they stop coinciding.
 *
 * A no-op if [exileId] is not in exile (CR 603.10).
 */
fun returnExiledToBattlefield(
    state: GameState,
    exileId: ObjectId,
): GameState {
    val index = state.sharedZones.exile.indexOfFirst { it.id == exileId }
    if (index < 0) return state
    val exiled = state.sharedZones.exile[index]
    val (battlefieldId, allocated) = state.allocateObjectId()
    val reborn = GameObject(id = battlefieldId, card = exiled.card, owner = exiled.owner)
    val onBattlefield =
        allocated
            .updateExile { it.removingAt(index) }
            .updateBattlefield { it.adding(reborn) }
    return announceBattlefieldEntry(
        onBattlefield,
        battlefieldId,
        GameEvent.PermanentEntered(exiled.owner, exileId, exiled.card, battlefieldId),
    )
}

/**
 * Effect primitive: returns the exile object [exileId] to its owner's **hand** as a new object
 * (CR 400.7) — the published building block Mesmeric Fiend's "return the exiled card to its owner's
 * hand" composes (ADR-003).
 *
 * Emits [GameEvent.CardReturnedToHandFromExile] rather than [GameEvent.CardReturnedToHand], because the
 * source zone is the load-bearing half of a return and a driver narrating "returned from the graveyard"
 * for a card that came back from exile would be describing a different game.
 *
 * The card becomes **hidden again** on arrival (CR 402.1): it was public in exile, and a seat's view of
 * an opponent's hand is a count, so nothing special is needed to re-hide it — the per-seat filter
 * (ADR-007) does it by construction, which is the payoff of filtering by zone rather than by card.
 *
 * A no-op if [exileId] is not in exile (CR 603.10).
 */
fun returnExiledToOwnersHand(
    state: GameState,
    exileId: ObjectId,
): GameState {
    val index = state.sharedZones.exile.indexOfFirst { it.id == exileId }
    if (index < 0) return state
    val exiled = state.sharedZones.exile[index]
    val (handId, allocated) = state.allocateObjectId()
    val reborn = GameObject(id = handId, card = exiled.card, owner = exiled.owner)
    return allocated
        .updateExile { it.removingAt(index) }
        .updatePlayer(exiled.owner) { it.copy(hand = it.hand.adding(reborn)) }
        .emit(GameEvent.CardReturnedToHandFromExile(exiled.owner, handId, exiled.card))
}

/**
 * Effect primitive: **flickers** the battlefield permanent [objectId] — exiles it and immediately
 * returns it to the battlefield under its owner's control (CR 701.3a then CR 400.7) — the published
 * building block Ephemerate's "exile target creature you control, then return it to the battlefield
 * under its owner's control" composes (ADR-003).
 *
 * **One resolution, two zone changes, three objects.** The permanent is exiled (becoming a new object),
 * then that exile object is returned (becoming a third). Nothing marks the exile object and nothing
 * needs to: the return happens inside this single step, with no window in which any player could act,
 * so unlike Journey to Nowhere's exile there is no interval during which the link has to be *remembered*
 * — which is exactly why flicker needs no CR 607 linked-ability record and Journey to Nowhere does.
 *
 * The observable consequences are all CR 400.7's, not this function's: the permanent comes back with no
 * counters (CR 122.2), no marked damage, untapped, summoning sick (CR 302.6), stripped of any Aura that
 * was on it (which falls off at the next CR 704.5m check), and with its enters-the-battlefield abilities
 * **re-fired** (CR 603.6a). A **token** flickered this way does not come back at all: it ceases to exist
 * at the next CR 704.5d state-based check, and the return finds nothing in exile to return.
 *
 * Fails loudly if [objectId] is not on the battlefield, for [exilePermanent]'s reason: every caller
 * reaches this after the CR 608.2b re-check confirmed a legal battlefield target (ADR-005).
 */
fun flickerPermanent(
    state: GameState,
    objectId: ObjectId,
): GameState {
    val exiled = exilePermanentReturningId(state, objectId)
    return returnExiledToBattlefield(exiled.state, exiled.exileId)
}
