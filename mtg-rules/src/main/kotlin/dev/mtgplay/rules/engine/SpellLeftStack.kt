package dev.mtgplay.rules.engine

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry

/*
 * The one move a spell's card makes when it leaves the stack, whatever took it off (CR 608.2m, CR 701.5a,
 * CR 702.34e): out of the stack, into its owner's graveyard as a **new** object (CR 400.7) — or into
 * exile, if it was cast via a permission that exiles it instead as it leaves the stack (flashback,
 * CR 702.34e).
 *
 * Extracted from `StackResolution.kt` by `FW-COUNTER`. The resolution paths (CR 608.2m on resolution,
 * CR 608.2b on a fizzle) reach it with the departing spell **topmost**, which CR 608.1 guarantees and
 * their own callers assert; the counter path (CR 701.5a) reaches it with the spell **anywhere on the
 * stack**, because a countered spell need not be directly below the counter — two counters can stack
 * above one spell, and a triggered counter sits above whatever was on the stack when it fired. So the
 * move itself is position-agnostic and locates the departing entry by its stack-residence id, while the
 * topmost-ness assertion stays at the two call sites that can honestly make it.
 *
 * `castVia.exilesOnLeaveStack` has promised since P5.2 that it covers "resolution, **countering**, and
 * fizzling". This file is where that promise is kept once, for all three.
 */

/**
 * The outcome of a spell's card leaving the stack.
 *
 * @property state the successor state, with the entry gone from the stack and its card in its new zone.
 * @property newObjectId the id the card was reborn under in that zone (CR 400.7) — or, for a **copy**
 *   that ceased to exist (CR 707.10a), the id it had on the stack, since nothing was reborn.
 * @property exiled whether it went to exile rather than a graveyard (CR 702.34e); the caller narrates.
 * @property ceased whether the departing spell was a copy and so simply stopped existing (`W9-C`).
 */
internal data class SpellLeftStack(
    val state: GameState,
    val newObjectId: ObjectId,
    val exiled: Boolean,
    val ceased: Boolean = false,
)

/**
 * Takes the spell [entry] off the stack from wherever it sits and puts its card into its owner's
 * graveyard as a new object (CR 400.7) — **unless** it was cast via a permission that exiles it instead
 * as it leaves the stack (flashback, CR 702.34e), in which case it goes to exile.
 *
 * Locates the entry by its stack-residence object id, never by position: see the file comment. Fails
 * loudly if it is not on the stack at all, which would mean the caller is moving a card that has already
 * left (an engine defect, and the exact double-move a position-based removal would perform silently).
 *
 * @param exileInstead forces the exile destination for a departure that is *not* a property of how the
 *   spell was cast. **Rebound** (CR 702.88a) is the only caller: unlike flashback, whose
 *   `exilesOnLeaveStack` covers every way a spell leaves the stack, rebound replaces the graveyard move
 *   *only as the spell resolves* — a countered or fizzled Ephemerate goes to the graveyard and does not
 *   rebound. So the condition cannot live on [dev.mtgplay.core.definition.CastingPermission], where it
 *   would silently also fire on the counter and fizzle paths; it is decided by the resolution caller and
 *   passed in.
 */
internal fun putSpellOffStack(
    state: GameState,
    entry: StackEntry.Spell,
    exileInstead: Boolean = false,
): SpellLeftStack {
    val index = state.sharedZones.stack.indexOfFirst { (it as? StackEntry.Spell)?.obj?.id == entry.obj.id }
    check(index >= 0) {
        "CR 400.7: ${entry.obj.card.name} (${entry.obj.id}) is not on the stack, so it cannot leave it"
    }
    // CR 707.10a / CR 704.5e: a copy of a spell is not a card, so there is nothing to put anywhere — it
    // leaves the stack and ceases to exist. The CR reaches the same place by moving it to a graveyard and
    // then having a state-based action delete it, which is unobservable here: state-based actions run
    // before any player receives priority, so no card in the pool can see a copy sitting in a graveyard.
    // Doing it in one step also keeps the card census exact, since a copy is not a conserved card at all.
    if (entry.isCopy) {
        val ceased = state.updateStack { it.removingAt(index) }
        return SpellLeftStack(ceased, entry.obj.id, exiled = false, ceased = true)
    }
    val exilesInstead = entry.castVia?.exilesOnLeaveStack == true || exileInstead
    val (id, allocated) = state.allocateObjectId()
    val reborn = entry.obj.copy(id = id)
    val destacked = allocated.updateStack { it.removingAt(index) }
    val moved =
        if (exilesInstead) {
            destacked.updateExile { it.adding(reborn) }
        } else {
            destacked.updatePlayer(entry.obj.owner) { it.copy(graveyard = it.graveyard.adding(reborn)) }
        }
    return SpellLeftStack(moved, id, exilesInstead)
}

/** Emits the flashback exile-instead event (CR 702.34e) when the departing spell left the stack to exile. */
internal fun narrateLeaveStackExile(
    state: GameState,
    entry: StackEntry.Spell,
    left: SpellLeftStack,
): GameState =
    if (left.exiled) {
        state.emit(
            GameEvent.SpellExiledInsteadOfGraveyard(entry.controller, entry.obj.id, entry.obj.card, left.newObjectId),
        )
    } else {
        state
    }
