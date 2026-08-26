package dev.mtgplay.rules.engine

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.random.shuffled
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry

/*
 * The one move a spell's card makes when it leaves the stack, whatever took it off (CR 608.2m, CR 701.5a,
 * CR 702.34e): out of the stack, into its owner's graveyard as a **new** object (CR 400.7) — or into
 * exile, if it was cast via a permission that exiles it instead as it leaves the stack (flashback,
 * CR 702.34e) — or, since `W10-B`, shuffled into its owner's library, which is what an Omen spell does
 * as it resolves (CR 720.3d).
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
    val destination: LeaveStackDestination,
    val ceased: Boolean = false,
) {
    /** Whether the card went to exile rather than a graveyard or a library (CR 702.34e, CR 715.3d). */
    val exiled: Boolean get() = destination == LeaveStackDestination.EXILE
}

/**
 * Where a spell's card goes as it leaves the stack (CR 608.2m) — the three zones the pool's cards can
 * send it to. Additive (`W10-B`, which added the third).
 *
 * A named axis rather than the pair of booleans it replaced, because there are now three destinations
 * and two independent flags would make a fourth, meaningless combination representable.
 */
internal enum class LeaveStackDestination {
    /** CR 608.2m's default: the card is put into its owner's graveyard. */
    OWNERS_GRAVEYARD,

    /**
     * The card is exiled instead — a flashback spell however it leaves the stack (CR 702.34e), a
     * rebounding or Adventure spell only as it resolves (CR 702.88a, CR 715.3d).
     */
    EXILE,

    /**
     * The card is **shuffled into its owner's library** instead (CR 720.3d) — an Omen spell resolving.
     * The only destination that consumes seeded entropy, because putting the card back without
     * randomising would leave its position known to everyone who watched it go (ADR-006).
     */
    OWNERS_LIBRARY,
}

/**
 * Takes the spell [entry] off the stack from wherever it sits and puts its card into its owner's
 * graveyard as a new object (CR 400.7) — **unless** it was cast via a permission that exiles it instead
 * as it leaves the stack (flashback, CR 702.34e), in which case it goes to exile.
 *
 * Locates the entry by its stack-residence object id, never by position: see the file comment. Fails
 * loudly if it is not on the stack at all, which would mean the caller is moving a card that has already
 * left (an engine defect, and the exact double-move a position-based removal would perform silently).
 *
 * @param instead forces a destination for a departure that is *not* a property of how the spell was
 *   cast, or `null` to take the destination from the cast record. The **resolution** paths are the only
 *   callers that pass one, and all three of their mechanics need it: unlike flashback, whose
 *   `exilesOnLeaveStack` covers every way a spell leaves the stack, rebound (CR 702.88a), an Adventure
 *   (CR 715.3d) and an Omen (CR 720.3d) replace the graveyard move *only as the spell resolves* — a
 *   countered or fizzled Ephemerate, Forktail Sweep or Sagu Wilds goes to the graveyard like anything
 *   else. So the condition cannot live on [dev.mtgplay.core.definition.CastingPermission], where it
 *   would silently also fire on the counter and fizzle paths; it is decided by the resolution caller and
 *   passed in.
 */
internal fun putSpellOffStack(
    state: GameState,
    entry: StackEntry.Spell,
    instead: LeaveStackDestination? = null,
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
        return SpellLeftStack(ceased, entry.obj.id, LeaveStackDestination.OWNERS_GRAVEYARD, ceased = true)
    }
    val destination =
        instead
            ?: if (entry.castVia?.exilesOnLeaveStack == true) {
                LeaveStackDestination.EXILE
            } else {
                LeaveStackDestination.OWNERS_GRAVEYARD
            }
    val (id, allocated) = state.allocateObjectId()
    val reborn = entry.obj.copy(id = id)
    val destacked = allocated.updateStack { it.removingAt(index) }
    val moved =
        when (destination) {
            LeaveStackDestination.EXILE -> destacked.updateExile { it.adding(reborn) }
            LeaveStackDestination.OWNERS_GRAVEYARD ->
                destacked.updatePlayer(entry.obj.owner) { it.copy(graveyard = it.graveyard.adding(reborn)) }
            LeaveStackDestination.OWNERS_LIBRARY -> shuffleDepartingSpellIntoLibrary(destacked, reborn)
        }
    return SpellLeftStack(moved, id, destination)
}

/**
 * Puts the departing card [reborn] into its owner's library and randomises the whole library
 * (CR 720.3d, CR 701.20) — the Omen half of [putSpellOffStack].
 *
 * **The card and the shuffle are one operation**, the discipline
 * [dev.mtgplay.rules.effect.shuffleIntoOwnersLibrary] already states: adding it and shuffling separately
 * would be the same states in the same order, but writing it as one step is what stops a caller from
 * doing the first half alone and quietly putting a card every player just watched resolve on the bottom
 * of a known library.
 *
 * The randomisation draws from the **match-owned** [dev.mtgplay.core.random.Rng] and returns its
 * successor on the state (ADR-006), so a replay of the same seed reproduces the same library order and
 * the resulting order appears in no seat view and in no event.
 *
 * **Owner, not controller.** CR 720.3d: *"its controller shuffles it into its **owner's** library"* —
 * the shuffling is done by one player and the library belongs to another whenever a spell was cast from
 * a zone its owner does not control. Nothing in the pool separates them, and the code names the owner
 * because that is the half the rule fixes.
 */
private fun shuffleDepartingSpellIntoLibrary(
    state: GameState,
    reborn: GameObject,
): GameState {
    val owner = reborn.owner
    val inLibrary = state.updatePlayer(owner) { it.copy(library = it.library.adding(reborn)) }
    val (shuffled, nextRng) = inLibrary.player(owner).library.shuffled(inLibrary.rng)
    return inLibrary
        .copy(rng = nextRng)
        .updatePlayer(owner) { it.copy(library = shuffled) }
        .emit(GameEvent.CardShuffledIntoLibrary(owner, reborn.id, reborn.card))
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
