package dev.mtgplay.rules.effect

import dev.mtgplay.core.definition.LibraryPosition
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.announceBattlefieldDeparture
import dev.mtgplay.rules.engine.clearCombatReferences
import dev.mtgplay.rules.engine.emit
import dev.mtgplay.rules.engine.updateBattlefield
import dev.mtgplay.rules.engine.updatePlayer
import kotlinx.collections.immutable.PersistentList

/**
 * Effect primitive: puts the battlefield permanent [objectId] into **its owner's library** at
 * [position] (CR 400.7, CR 401.1) — the building block Deem Inferior's *"The owner of target nonland
 * permanent puts it into their library second from the top or on the bottom"* composes (ADR-003).
 * Additive, flagged (`W9-F`).
 *
 * **The fifth battlefield-exit primitive, and separate from the other four for the reason they are
 * separate from each other** ([destroy], [exilePermanent], [sacrificePermanent],
 * [returnPermanentToOwnersHand]): each names a different destination, and a destination parameter would
 * put five genuinely different outcomes behind one name. It is also **not** a widening of
 * [putGraveyardCardOnTopOfOwnersLibrary], which starts in a graveyard: a permanent leaving the
 * battlefield drags consequences a graveyard card has none of, and they are exactly the ones
 * [returnPermanentToOwnersHand] enumerates and this function repeats.
 *
 * Everything that leaving the battlefield entails happens here and is easy to omit silently:
 * - the card is **reborn under a fresh object id** (CR 400.7) — the battlefield object is gone, and with
 *   it every counter, every marked damage, and every attachment;
 * - **CR 603.6c leaves-the-battlefield triggers fire** against the pre-move state, carrying the departed
 *   permanent's last-known information, with `graveyardId = null` because nothing reached a graveyard;
 * - **CR 506.4** removes it from combat;
 * - an Aura left attached to nothing is put into its owner's graveyard by the next CR 704.5m check,
 *   which the state-based-action pass owns rather than this function;
 * - **indestructible is not consulted** (CR 702.12b) — this is a zone change, not destruction, and it is
 *   the reason the card answers permanents that removal does not.
 *
 * **Owner, not controller** (CR 108.3): the card joins the library of the player who owns it, which is
 * what the printed line says and which may differ from whoever controlled the permanent.
 *
 * Fails loudly if [objectId] is not on the battlefield: every caller reaches this after the CR 608.2b
 * re-check has confirmed a legal battlefield target (ADR-005), so a missing one is an engine defect.
 */
fun putPermanentIntoOwnersLibrary(
    state: GameState,
    objectId: ObjectId,
    position: LibraryPosition,
): GameState {
    val battlefield = state.sharedZones.battlefield
    val index = battlefield.indexOfFirst { it.id == objectId }
    require(index >= 0) {
        "CR 400.7: a permanent put into a library must be on the battlefield, but $objectId is not"
    }
    val permanent = battlefield[index]
    val (libraryId, allocated) = state.allocateObjectId()
    val reborn = GameObject(id = libraryId, card = permanent.card, owner = permanent.owner)
    val moved =
        allocated
            .updateBattlefield { it.removingAt(index) }
            .updatePlayer(permanent.owner) { it.copy(library = it.library.seatAt(reborn, position)) }
            .emit(GameEvent.PermanentPutIntoLibrary(permanent.owner, libraryId, permanent.card, position))
    val triggered = announceBattlefieldDeparture(moved, permanent, graveyardId = null)
    return clearCombatReferences(triggered, setOf(objectId))
}

/**
 * Seats [obj] at [position] in this library (CR 401.1 — index 0 is the top).
 *
 * **[LibraryPosition.SECOND_FROM_TOP] on a library of fewer than two cards lands on top**, which is the
 * only reading "second from the top" has when there is no first card to be second to — `addingAt` at an
 * index past the end would throw, and a library that small belongs to a player who is about to lose to
 * CR 704.5b anyway.
 */
private fun PersistentList<GameObject>.seatAt(
    obj: GameObject,
    position: LibraryPosition,
): PersistentList<GameObject> =
    when (position) {
        LibraryPosition.SECOND_FROM_TOP -> addingAt(minOf(SECOND_FROM_TOP_INDEX, size), obj)
        LibraryPosition.BOTTOM -> adding(obj)
    }

/** The library index one card below the top (CR 401.1 — index 0 is the top). */
private const val SECOND_FROM_TOP_INDEX: Int = 1
