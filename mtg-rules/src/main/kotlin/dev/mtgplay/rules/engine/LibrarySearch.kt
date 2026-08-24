package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.LibrarySearch
import dev.mtgplay.core.definition.LibrarySearchFilter
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.shuffled
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingLibrarySearch
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId

/*
 * The "search your library for a matching card, reveal it, put it into your hand, then shuffle" flow
 * (CR 701.18) — Ash Barrens' basic landcycling. Part of an activated ability's resolution: the ability stays
 * on top of the stack (like the library-reveal flow) so its declaration is a pure derivation of the state
 * (ADR-004). When a matching card exists the engine pauses for the find-one choice (failing to find is
 * always legal, CR 701.18b); the found card is revealed (public information) and moved to the hand, then the
 * library is shuffled through the match PRNG (ADR-006 — the shuffle consumes seeded entropy, so replay
 * reproduces the new order), and the ability ceases to exist (CR 113.7a).
 */

/**
 * Runs an activated ability's library search (CR 701.18): if a matching card is in the library the engine
 * pauses for the find-one choice; otherwise nothing is found, the library is still shuffled, and the ability
 * ceases. The resolving ability [entry] stays on top of the stack during any pause.
 */
internal fun orchestrateLibrarySearch(
    state: GameState,
    entry: StackEntry.ActivatedAbilityOnStack,
    search: LibrarySearch,
): AdvanceResult {
    val decider = entry.controller
    // CR 701.18b: with no matching card there is nothing to find — the library is shuffled and the ability ends.
    if (matchingLibraryCards(state, decider, search.toHand).isEmpty()) {
        return completeSearch(shuffleLibrary(state, decider), entry)
    }
    val paused = state.copy(pendingLibrarySearch = PendingLibrarySearch(decider))
    return AdvanceResult.NeedsDecision(paused, pendingLibrarySearchRequest(paused))
}

/**
 * The find-one request the open [GameState.pendingLibrarySearch] is waiting on (CR 701.18): the matching
 * library cards plus a "find none". Pure per ADR-004 — the resolving ability (with its search) is the top of
 * the stack and the library is unchanged.
 */
internal fun pendingLibrarySearchRequest(state: GameState): DecisionRequest.ChooseFromLibrary {
    val pending = state.pendingLibrarySearch ?: error("no library search is pending")
    val search = resolvingSearch(state)
    val matches = matchingLibraryCards(state, pending.decider, search.toHand)
    return DecisionRequest.ChooseFromLibrary(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        options = matches.map { DecisionRequest.ChooseFromLibrary.Option(it.id, it.card) },
    )
}

/**
 * Applies the find-one choice (CR 701.18): puts [foundObjectId] (or none) from the library into the
 * decider's hand as a revealed new object (CR 400.7), shuffles the library through the match PRNG (ADR-006),
 * then the resolving ability ceases to exist.
 */
internal fun applyLibrarySearchChoice(
    state: GameState,
    foundObjectId: ObjectId?,
): AdvanceResult {
    val pending = state.pendingLibrarySearch ?: error("no library search is pending")
    val entry =
        state.sharedZones.stack.lastOrNull() as? StackEntry.ActivatedAbilityOnStack
            ?: error("CR 608.1: a library search requires a resolving activated ability on top of the stack")
    val cleared = state.copy(pendingLibrarySearch = null)
    val withCard =
        if (foundObjectId == null) cleared else moveLibraryCardToHand(cleared, pending.decider, foundObjectId)
    return completeSearch(shuffleLibrary(withCard, pending.decider), entry)
}

/** Finishes a library search (CR 113.7a): the resolving ability leaves the stack and a fresh priority round opens. */
private fun completeSearch(
    state: GameState,
    entry: StackEntry.ActivatedAbilityOnStack,
): AdvanceResult {
    check(state.sharedZones.stack.lastOrNull() == entry) { "CR 608.1: only the topmost stack object may resolve" }
    val ceased = state.updateStack { it.removingAt(it.lastIndex) }
    return grantPriorityRound(ceased.emit(GameEvent.AbilityResolved(entry.controller, entry.sourceCard)))
}

/**
 * Moves the library object [objectId] of [player] into their hand as a **new** object (CR 400.7, CR 701.18):
 * the found card is revealed (public, [GameEvent.CardsRevealed]) and then put into the hand (reusing
 * [GameEvent.CardReturnedToHand] as the generic move-to-hand event, as the library-reveal flow does).
 */
private fun moveLibraryCardToHand(
    state: GameState,
    player: PlayerId,
    objectId: ObjectId,
): GameState {
    val library = state.player(player).library
    val index = library.indexOfFirst { it.id == objectId }
    require(index >= 0) { "CR 701.18: the found card $objectId is no longer in $player's library" }
    val found = library[index]
    val (newId, allocated) = state.allocateObjectId()
    val reborn = GameObject(id = newId, card = found.card, owner = found.owner)
    return allocated
        .emit(GameEvent.CardsRevealed(player, listOf(found.card)))
        .updatePlayer(player) { it.copy(library = it.library.removingAt(index), hand = it.hand.adding(reborn)) }
        .emit(GameEvent.CardReturnedToHand(player, newId, found.card))
}

/** Shuffles [player]'s library through the match PRNG (CR 701.18, ADR-006); the seeded draw makes replay reproduce. */
private fun shuffleLibrary(
    state: GameState,
    player: PlayerId,
): GameState {
    val (shuffled, nextRng) = state.player(player).library.shuffled(state.rng)
    return state.copy(rng = nextRng).updatePlayer(player) { it.copy(library = shuffled) }
}

/** The library cards of [player] matching [filter] (CR 701.18), in library (top-first) order. */
private fun matchingLibraryCards(
    state: GameState,
    player: PlayerId,
    filter: LibrarySearchFilter,
): List<GameObject> = state.player(player).library.filter { matchesSearchFilter(state, it, filter) }

/** Whether the library [obj] matches the search [filter] (CR 701.18) — read from its printed characteristics. */
private fun matchesSearchFilter(
    state: GameState,
    obj: GameObject,
    filter: LibrarySearchFilter,
): Boolean =
    when (filter) {
        LibrarySearchFilter.BASIC_LAND_CARD -> {
            val characteristics = state.definitions[obj.card]?.characteristics
            characteristics != null &&
                CardType.LAND in characteristics.cardTypes &&
                Supertype.BASIC in characteristics.supertypes
        }
        // CR 205.3b, CR 702.28b: typecycling names a land *subtype*; the basic supertype is not required.
        LibrarySearchFilter.ISLAND_CARD ->
            ISLAND in
                state.definitions[obj.card]
                    ?.characteristics
                    ?.subtypes
                    .orEmpty()
    }

/** The Island land type (CR 205.3b) an [LibrarySearchFilter.ISLAND_CARD] search matches on. */
private val ISLAND: Subtype = Subtype("Island")

/** The library search of the resolving activated ability on top of the stack (CR 701.18); fails loudly. */
private fun resolvingSearch(state: GameState): LibrarySearch =
    (state.sharedZones.stack.lastOrNull() as? StackEntry.ActivatedAbilityOnStack)?.ability?.librarySearch
        ?: error("CR 701.18: a library search requires a resolving activated ability with a search clause on the stack")
