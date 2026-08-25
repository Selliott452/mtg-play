package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.LibraryLookMode
import dev.mtgplay.core.definition.LibraryLookSource
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.DecisionRequest
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList

/*
 * Moving a looked-at pool to the destinations a chosen arrangement names (CR 701.17a, CR 400.7). Split from
 * LibraryLook.kt, which owns the orchestration and the pauses; this file owns the zone mechanics, whose one
 * governing rule is CR 400.7 read both ways: a card that changes zones is reborn with a fresh object id and
 * is narrated, and a card that only moves *within* a zone keeps its id and stays silent — its new position
 * is private to the player who chose it (CR 701.14a).
 */

/**
 * Moves the pool to its chosen destinations (CR 400.7). The pool is lifted out of its source zone first, so
 * the surviving zone is what everything is re-seated around and both source zones keep their remaining
 * order; then the hand, the top of the library, and the bottom of the library are filled in that order.
 */
internal fun distributeArrangement(
    state: GameState,
    player: PlayerId,
    source: LibraryLookSource,
    poolIds: List<ObjectId>,
    option: DecisionRequest.ChooseLibraryArrangement.Option,
): GameState {
    val pool = poolIds.map { id -> poolObject(state, player, id) }
    val lifted =
        state.updatePlayer(player) { current ->
            when (source) {
                LibraryLookSource.TOP_OF_LIBRARY ->
                    current.copy(library = current.library.filterNot { it.id in poolIds }.toPersistentList())
                LibraryLookSource.HAND ->
                    current.copy(hand = current.hand.filterNot { it.id in poolIds }.toPersistentList())
            }
        }
    val handed =
        option.toHand.map { pool[it] }.fold(lifted) { s, obj -> putArrangedIntoHand(s, player, obj, source) }
    // CR 701.44a: surveil's graveyard destination, filled before the library so that the library's own
    // re-seating is not disturbed by a second pass over it. Empty for every other mode.
    val binned =
        option.toGraveyard.map { pool[it] }.fold(handed) { s, obj -> putArrangedIntoGraveyard(s, player, obj) }
    // Right-to-left, because each is inserted at index 0: the first entry ends up topmost (CR 401.1).
    val topped =
        option.toTop.map { pool[it] }.foldRight(binned) { obj, s ->
            putArrangedOnLibrary(s, player, obj, source, onTop = true)
        }
    return option.toBottom.map { pool[it] }.fold(topped) { s, obj ->
        putArrangedOnLibrary(s, player, obj, source, onTop = false)
    }
}

/**
 * Puts one surveilled card into [player]'s graveyard (CR 701.44a, CR 400.7). Always a zone change from a
 * library pool, so the card is reborn with a fresh object id and narrated with
 * [GameEvent.CardSurveilled] — its own event and not the CR 701.13a mill's, because the two are different
 * game actions however alike they look from the outside.
 *
 * **The one destination of this framework that is public.** A graveyard is a public zone (CR 400.2), so
 * the moment a looked-at card lands there its identity is known to everybody; the cards kept on top stay
 * private (CR 701.14a), which is exactly the asymmetry surveil trades on.
 */
private fun putArrangedIntoGraveyard(
    state: GameState,
    player: PlayerId,
    obj: GameObject,
): GameState {
    val (newId, allocated) = state.allocateObjectId()
    val reborn = GameObject(id = newId, card = obj.card, owner = obj.owner)
    return allocated
        .updatePlayer(player) { it.copy(graveyard = it.graveyard.adding(reborn)) }
        .emit(GameEvent.CardSurveilled(player, newId, obj.card))
}

/**
 * Emits the CR 701.16a reveal a **filtered** look owes for the cards it keeps — Ancient Stirrings' "You may
 * reveal a colorless card from among them", Lead the Stampede's "any number of creature cards" — and nothing
 * at all for any other mode, or for a filtered mode that kept nothing.
 *
 * **The one place this framework is partly public, and the boundary is exact.** Only the cards named by
 * [option]`.toHand` are revealed; the cards going to the bottom of the library keep both their identities
 * and their chosen order private (CR 701.14a), so an opponent learns *which* cards were taken and never how
 * deep the rest were stacked. That is precisely what the printed word "reveal" buys them. The event is the
 * *only* channel — [dev.mtgplay.core.event.GameEvent] is derived observability (ADR-006) and is not part of
 * any seat view, and the kept cards reach the opponent's view a moment later anyway as they enter a hand,
 * whose size is public.
 */
internal fun announceRevealedKeeps(
    state: GameState,
    decider: PlayerId,
    mode: LibraryLookMode,
    poolIds: List<ObjectId>,
    option: DecisionRequest.ChooseLibraryArrangement.Option,
): GameState =
    if (mode !is LibraryLookMode.RevealMatchingToHandRestToBottom || option.toHand.isEmpty()) {
        state
    } else {
        state.emit(
            GameEvent.CardsRevealed(decider, option.toHand.map { poolObject(state, decider, poolIds[it]).card }),
        )
    }

/** The pool object [id] in its source zone; fails loudly rather than guessing at a moved card. */
internal fun poolObject(
    state: GameState,
    player: PlayerId,
    id: ObjectId,
): GameObject {
    val seat = state.player(player)
    return (seat.library.firstOrNull { it.id == id } ?: seat.hand.firstOrNull { it.id == id })
        ?: error("CR 701.14a: looked-at card $id is no longer in $player's library or hand")
}

/**
 * Puts one arranged card into [player]'s hand. From a library pool that is a zone change (CR 400.7): a new
 * object, narrated with [GameEvent.CardReturnedToHand] (the generic move-to-hand event, as the reveal flow
 * uses it). From a hand pool the card never left the hand, so it is simply re-seated with its id intact.
 */
private fun putArrangedIntoHand(
    state: GameState,
    player: PlayerId,
    obj: GameObject,
    source: LibraryLookSource,
): GameState =
    when (source) {
        LibraryLookSource.HAND -> state.updatePlayer(player) { it.copy(hand = it.hand.adding(obj)) }
        LibraryLookSource.TOP_OF_LIBRARY -> {
            val (newId, allocated) = state.allocateObjectId()
            val reborn = GameObject(id = newId, card = obj.card, owner = obj.owner)
            allocated
                .updatePlayer(player) { it.copy(hand = it.hand.adding(reborn)) }
                .emit(GameEvent.CardReturnedToHand(player, newId, obj.card))
        }
    }

/**
 * Puts one arranged card onto [player]'s library — [onTop] at index 0, otherwise at the end (CR 401.1). A
 * card from a library pool never left the library, so it keeps its id and stays silent: its new position is
 * private to the player who chose it (CR 701.14a). A card from a hand pool changed zones, so it is reborn
 * (CR 400.7) and narrated with [GameEvent.CardPutOnLibrary].
 */
private fun putArrangedOnLibrary(
    state: GameState,
    player: PlayerId,
    obj: GameObject,
    source: LibraryLookSource,
    onTop: Boolean,
): GameState =
    when (source) {
        LibraryLookSource.TOP_OF_LIBRARY ->
            state.updatePlayer(player) { it.copy(library = it.library.seat(obj, onTop)) }
        LibraryLookSource.HAND -> {
            val (newId, allocated) = state.allocateObjectId()
            val reborn = GameObject(id = newId, card = obj.card, owner = obj.owner)
            allocated
                .updatePlayer(player) { it.copy(library = it.library.seat(reborn, onTop)) }
                .emit(GameEvent.CardPutOnLibrary(player, newId, obj.card, onTop))
        }
    }

/** Seats [obj] at the top (index 0) or the bottom of a library (CR 401.1 — index 0 is the top). */
private fun PersistentList<GameObject>.seat(
    obj: GameObject,
    onTop: Boolean,
): PersistentList<GameObject> = if (onTop) addingAt(0, obj) else adding(obj)
