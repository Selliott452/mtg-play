package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.LibrarySearch
import dev.mtgplay.core.definition.LibrarySearchDestination
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.shuffled
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingLibrarySearch
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.resolutionClauses
import dev.mtgplay.core.state.resolutionController
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId

/*
 * The "search your library for a matching card, put it somewhere, then shuffle" flow (CR 701.18) —
 * Ash Barrens' basic landcycling, the Landscape cycle's sacrifice ability, Crop Rotation
 * (docs/design/library-search.md).
 *
 * One of the five post-resolution clauses (`FW-CLAUSEHOOK`), so the resolving object stays on top of the
 * stack while the search pauses and its declaration is a pure derivation of the state (ADR-004). When a
 * matching card exists the engine pauses for the find-one choice (failing to find is always legal,
 * CR 701.18b); the found card is moved to the clause's destination, the library is shuffled through the
 * match PRNG (ADR-006 — the shuffle consumes seeded entropy, so replay reproduces the new order), and the
 * resolving object then leaves the stack the way its own kind does (CR 608.2m / CR 113.7a).
 *
 * **ADR-007.** A library is a *hidden* zone (CR 400.2), so this flow's options are hidden-zone cards and
 * the `library-look.md` §3 ruling applies rather than the `graveyard-targeting.md` §3 one: the *fact* of
 * the search is public (`PendingLibrarySearch` carries only the decider), the *options* reach the deciding
 * seat alone through `DecisionView.Elsewhere`, and nothing here widens `SeatView.cards`. That was already
 * true of the search before this packet and stays true across both new destinations — a card put onto the
 * battlefield becomes public only once it is *there*, which is after the choice was made.
 */

/**
 * Runs a resolving object's library search (CR 701.18): if a matching card is in the library the engine
 * pauses for the find-one choice; otherwise nothing is found, the library is still shuffled, and the
 * resolution completes. The resolving [entry] stays on top of the stack during any pause.
 */
internal fun orchestrateLibrarySearch(
    state: GameState,
    entry: StackEntry,
    search: LibrarySearch,
): AdvanceResult {
    val decider = entry.resolutionController
    // CR 701.18b: with no matching card a *mandatory* search has nothing to find — the library is
    // shuffled and the resolution ends. A "you may search" still pauses, because declining and
    // searching-then-failing differ by exactly that shuffle (LibrarySearch.optional).
    if (!search.optional && matchingLibraryCards(state, decider, search.find).isEmpty()) {
        return completeClauseResolution(shuffleLibrary(state, decider), entry)
    }
    val paused = state.copy(pendingLibrarySearch = PendingLibrarySearch(decider))
    return AdvanceResult.NeedsDecision(paused, pendingLibrarySearchRequest(paused))
}

/**
 * The find-one request the open [GameState.pendingLibrarySearch] is waiting on (CR 701.18): the matching
 * library cards plus a "find none". Pure per ADR-004 — the resolving object (with its search clause) is the
 * top of the stack and the library is unchanged.
 */
internal fun pendingLibrarySearchRequest(state: GameState): DecisionRequest.ChooseFromLibrary {
    val pending = state.pendingLibrarySearch ?: error("no library search is pending")
    val search = resolvingSearch(state)
    val matches = matchingLibraryCards(state, pending.decider, search.find)
    return DecisionRequest.ChooseFromLibrary(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        options = matches.map { DecisionRequest.ChooseFromLibrary.Option(it.id, it.card) },
        optionalSearch = search.optional,
    )
}

/**
 * Applies the find-one choice (CR 701.18): moves [foundObjectId] (or none) out of the library to the
 * clause's destination as a new object (CR 400.7), shuffles the library through the match PRNG (ADR-006),
 * then completes the resolution.
 *
 * [searched] is `false` only for the "don't search at all" index of a "you may search" (CR 601.3b), and
 * it is what suppresses the shuffle: the "then shuffle" belongs to a search that happened. A declined
 * search finds nothing *and* randomises nothing, which is the one thing that distinguishes it from
 * failing to find (see [dev.mtgplay.core.definition.LibrarySearch.optional]).
 */
internal fun applyLibrarySearchChoice(
    state: GameState,
    foundObjectId: ObjectId?,
    searched: Boolean = true,
): AdvanceResult {
    val pending = state.pendingLibrarySearch ?: error("no library search is pending")
    val entry = resolvingClauseEntry(state)
    val destination = resolvingSearch(state).destination
    require(searched || foundObjectId == null) {
        "CR 701.18: a declined search finds nothing, but $foundObjectId was reported found"
    }
    val cleared = state.copy(pendingLibrarySearch = null)
    val withCard =
        if (foundObjectId == null) {
            cleared
        } else {
            moveFoundCard(cleared, pending.decider, foundObjectId, destination)
        }
    val shuffled = if (searched) shuffleLibrary(withCard, pending.decider) else withCard
    return completeClauseResolution(shuffled, entry)
}

/**
 * Moves the found library object [objectId] of [player] to [destination] as a **new** object
 * (CR 400.7, CR 701.18) — exhaustive over the destination, so a new one breaks compilation here rather
 * than silently landing in a hand.
 */
private fun moveFoundCard(
    state: GameState,
    player: PlayerId,
    objectId: ObjectId,
    destination: LibrarySearchDestination,
): GameState =
    when (destination) {
        LibrarySearchDestination.REVEALED_TO_HAND ->
            moveLibraryCardToHand(state, player, objectId)
        LibrarySearchDestination.BATTLEFIELD ->
            moveLibraryCardToBattlefield(state, player, objectId, forcedTapped = false)
        LibrarySearchDestination.BATTLEFIELD_TAPPED ->
            moveLibraryCardToBattlefield(state, player, objectId, forcedTapped = true)
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
    val (found, removed) = takeLibraryCard(state, player, objectId)
    val (newId, allocated) = removed.allocateObjectId()
    val reborn = GameObject(id = newId, card = found.card, owner = found.owner)
    return allocated
        .emit(GameEvent.CardsRevealed(player, listOf(found.card)))
        .updatePlayer(player) { it.copy(hand = it.hand.adding(reborn)) }
        .emit(GameEvent.CardReturnedToHand(player, newId, found.card))
}

/**
 * Puts the library object [objectId] of [player] onto the battlefield under their control as a **new**
 * object (CR 400.7, CR 701.18) — Crop Rotation's and the Landscapes' destination. No reveal: the
 * battlefield is a public zone (CR 400.2), which is why no such card prints the word.
 *
 * **The tapped status has two sources and [forcedTapped] is the stronger one.** "Put it onto the
 * battlefield tapped" fixes the status outright (CR 110.5b); a plain "put it onto the battlefield" leaves
 * the CR 110.5a untapped default, which the entering permanent's *own* CR 614.1c clause may still replace
 * — Crop Rotation finding a Bridge land gets a tapped Bridge. Reading [entersTappedNow] before the object
 * joins the battlefield is what makes a conditional clause count the *other* permanents (Gingerbread
 * Cabin), exactly as the play-land path does.
 *
 * Entry and its CR 603.6a triggers go through [announceBattlefieldEntry], the single home every entry
 * path shares (triage T18).
 */
private fun moveLibraryCardToBattlefield(
    state: GameState,
    player: PlayerId,
    objectId: ObjectId,
    forcedTapped: Boolean,
): GameState {
    val (found, removed) = takeLibraryCard(state, player, objectId)
    val (battlefieldId, allocated) = removed.allocateObjectId()
    val tapped = forcedTapped || entersTappedNow(allocated, player, allocated.definitions[found.card])
    val entering = GameObject(id = battlefieldId, card = found.card, owner = found.owner, tapped = tapped)
    val onBattlefield = allocated.updateBattlefield { it.adding(entering) }
    return announceBattlefieldEntry(
        onBattlefield,
        battlefieldId,
        GameEvent.PermanentEntered(player, objectId, found.card, battlefieldId),
    )
}

/**
 * Removes the found object [objectId] from [player]'s library and returns it with the resulting state
 * (CR 701.18). Fails loudly if it is not there: the choice was enumerated off this very library moments
 * ago, and nothing may have moved it (ADR-005).
 */
private fun takeLibraryCard(
    state: GameState,
    player: PlayerId,
    objectId: ObjectId,
): Pair<GameObject, GameState> {
    val library = state.player(player).library
    val index = library.indexOfFirst { it.id == objectId }
    require(index >= 0) { "CR 701.18: the found card $objectId is no longer in $player's library" }
    return library[index] to state.updatePlayer(player) { it.copy(library = it.library.removingAt(index)) }
}

/** Shuffles [player]'s library through the match PRNG (CR 701.18, ADR-006); the seeded draw makes replay reproduce. */
private fun shuffleLibrary(
    state: GameState,
    player: PlayerId,
): GameState {
    val (shuffled, nextRng) = state.player(player).library.shuffled(state.rng)
    return state.copy(rng = nextRng).updatePlayer(player) { it.copy(library = shuffled) }
}

/** The library search clause of the resolving object on top of the stack (CR 701.18); fails loudly. */
private fun resolvingSearch(state: GameState): LibrarySearch =
    resolvingClauseEntry(state).resolutionClauses.librarySearch
        ?: error("CR 701.18: a library search requires a resolving object with a search clause on the stack")
