package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.definition.LibraryReveal
import dev.mtgplay.core.definition.RevealedCardFilter
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingRevealSelection
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.resolutionClauses
import dev.mtgplay.core.state.resolutionController
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import kotlinx.collections.immutable.toPersistentList

/*
 * Library manipulation (CR 701.16): reveal the top N cards, put up to M matching cards into the hand,
 * and put the rest into the graveyard — Malevolent Rumble (M = 1) and Kruphix's Insight (M = 3). The
 * keep choice is a mid-resolution decision, so the engine orchestrates the reveal around the pure
 * resolution effect. The clause is carried by
 * [dev.mtgplay.core.definition.ResolutionClauses], so a resolving ability reveals through this flow too
 * (`FW-CLAUSEHOOK`): the top N cards are revealed (public information, [GameEvent.CardsRevealed]) and,
 * if any match the filter, the resolving spell's controller is asked which to keep before the cards are
 * distributed. When nothing matches, all revealed cards go straight to the graveyard with no pause.
 *
 * "Put up to M into your hand" is one CR choice, but the engine surfaces choices as enumerated single
 * selections (ADR-005), so it is gathered as up to M rounds of "keep one of the remaining matching
 * cards, or keep no more" — the keeps accumulating in [PendingRevealSelection.keptIds]. Every legal
 * subset is reachable and no information is revealed between rounds (the whole reveal is public up
 * front and nothing moves until the last answer), so the reachable outcomes are exactly the CR's. The
 * loop ends on the first "keep no more", when the allowance is spent, or when no matching card remains.
 */

/** The set of card types that make a card a permanent card (CR 110.4a) — anything but instant and sorcery. */
private val PERMANENT_CARD_TYPES: Set<CardType> =
    setOf(CardType.ARTIFACT, CardType.CREATURE, CardType.ENCHANTMENT, CardType.LAND)

/**
 * Reveals the top [reveal].count cards of [entry]'s controller's library (CR 701.16), emitting
 * [GameEvent.CardsRevealed], then either pauses for the first keep choice (when at least one revealed
 * card matches [reveal].toHand) or, with no match, puts every revealed card into the graveyard and
 * finishes the spell. The revealed cards stay on top of the library during the pause.
 */
internal fun orchestrateLibraryReveal(
    state: GameState,
    entry: StackEntry,
    reveal: LibraryReveal,
): AdvanceResult {
    val controller = entry.resolutionController
    val revealed = state.player(controller).library.take(reveal.count)
    val announced =
        if (revealed.isEmpty()) state else state.emit(GameEvent.CardsRevealed(controller, revealed.map { it.card }))
    val candidates = revealed.filter { matchesFilter(state, it, reveal.toHand) }
    return if (candidates.isEmpty()) {
        // CR 701.16: nothing to keep — every revealed card goes to the graveyard, then the spell leaves.
        finishReveal(announced, entry, controller, revealed.map { it.id }, kept = emptySet())
    } else {
        val paused =
            announced.copy(
                pendingRevealSelection = PendingRevealSelection(controller, revealed.map { it.id }.toPersistentList()),
            )
        AdvanceResult.NeedsDecision(paused, pendingRevealRequest(paused))
    }
}

/**
 * The keep-one-more request the open [GameState.pendingRevealSelection] is waiting on (CR 701.16): the
 * revealed cards that still match the resolving spell's filter and are not already kept, plus a "keep
 * none" option. A pure function of the state (ADR-004) — the resolving spell (with its clause) is the
 * top of the stack, the revealed cards are still the top of the library, and the keeps so far are
 * recorded on the pending selection.
 */
internal fun pendingRevealRequest(state: GameState): DecisionRequest.ChooseFromRevealed {
    val pending = state.pendingRevealSelection ?: error("no reveal selection is pending")
    val reveal = revealClauseOf(state)
    val library = state.player(pending.decider).library
    val options =
        remainingCandidates(state, pending, reveal, library)
            .map { DecisionRequest.ChooseFromRevealed.Option(it.id, it.card) }
    return DecisionRequest.ChooseFromRevealed(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        options = options,
    )
}

/**
 * Applies one keep choice (CR 701.16). [keptObjectId] is the revealed library object to add to the
 * keeps, or `null` for "keep no more". The cards move only when the selection closes: on "keep no
 * more", on the keep that spends the clause's [LibraryReveal.toHandCount] allowance, or on the keep
 * after which no matching revealed card remains. Otherwise the engine pauses again for the next keep.
 */
internal fun applyRevealSelection(
    state: GameState,
    keptObjectId: ObjectId?,
): AdvanceResult {
    val pending = state.pendingRevealSelection ?: error("no reveal selection is pending")
    val entry = resolvingClauseEntry(state)
    val reveal = revealClauseOf(state)
    if (keptObjectId == null) {
        return finishReveal(state, entry, pending.decider, pending.revealedIds, pending.keptIds.toSet())
    }
    val updated = pending.copy(keptIds = pending.keptIds.adding(keptObjectId))
    val allowanceSpent = updated.keptIds.size >= reveal.toHandCount
    val advanced = state.copy(pendingRevealSelection = updated)
    val noneLeft =
        remainingCandidates(advanced, updated, reveal, advanced.player(updated.decider).library).isEmpty()
    return if (allowanceSpent || noneLeft) {
        finishReveal(advanced, entry, updated.decider, updated.revealedIds, updated.keptIds.toSet())
    } else {
        AdvanceResult.NeedsDecision(advanced, pendingRevealRequest(advanced))
    }
}

/**
 * Closes the reveal (CR 701.16): clears the pending selection, moves [kept] to [player]'s hand and every
 * other revealed card to their graveyard, and finishes the resolving object [entry] — a spell's CR 608.2m
 * graveyard move or an ability's CR 113.7a cessation, whichever [completeClauseResolution] says.
 */
private fun finishReveal(
    state: GameState,
    entry: StackEntry,
    player: PlayerId,
    revealedIds: List<ObjectId>,
    kept: Set<ObjectId>,
): AdvanceResult {
    val cleared = state.copy(pendingRevealSelection = null)
    val distributed = putRevealedIntoGraveyard(cleared, player, revealedIds, kept)
    return completeClauseResolution(distributed, entry)
}

/**
 * The [LibraryReveal] clause of the object resolving on top of the stack — the clause the open pending
 * selection belongs to. Fails loudly rather than guessing a filter or an allowance.
 */
private fun revealClauseOf(state: GameState): LibraryReveal =
    resolvingClauseEntry(state).resolutionClauses.libraryReveal
        ?: error("CR 701.16: a reveal selection requires a resolving object with a reveal clause on the stack")

/**
 * The revealed cards still keepable in [pending] (CR 701.16): those matching [reveal].toHand that are
 * not already kept, resolved against the decider's [library] in reveal (top-first) order.
 */
private fun remainingCandidates(
    state: GameState,
    pending: PendingRevealSelection,
    reveal: LibraryReveal,
    library: List<GameObject>,
): List<GameObject> =
    pending.revealedIds
        .filterNot { it in pending.keptIds }
        .mapNotNull { id -> library.firstOrNull { it.id == id } }
        .filter { matchesFilter(state, it, reveal.toHand) }

/**
 * Moves the revealed library cards [revealedIds] out of the [player]'s library (CR 400.7): those in
 * [keep] to the hand, the rest to the graveyard, both in reveal order. Each becomes a new object. Emits
 * a [GameEvent.CardReturnedToHand] for each kept card (reused as the generic move-to-hand event) and a
 * [GameEvent.CardDiscarded] for each to the graveyard (reused as the generic move-to-graveyard event).
 */
internal fun putRevealedIntoGraveyard(
    state: GameState,
    player: PlayerId,
    revealedIds: List<ObjectId>,
    keep: Set<ObjectId>,
): GameState =
    revealedIds.fold(state) { current, id ->
        val libraryObject =
            current.player(player).library.firstOrNull { it.id == id }
                ?: error("CR 701.16: revealed card $id is no longer in $player's library")
        val (newId, allocated) = current.allocateObjectId()
        val reborn = GameObject(id = newId, card = libraryObject.card, owner = libraryObject.owner)
        val withoutCard =
            allocated.updatePlayer(player) { p ->
                p.copy(library = p.library.removingAt(p.library.indexOfFirst { it.id == id }))
            }
        if (id in keep) {
            withoutCard
                .updatePlayer(player) { it.copy(hand = it.hand.adding(reborn)) }
                .emit(GameEvent.CardReturnedToHand(player, newId, libraryObject.card))
        } else {
            withoutCard
                .updatePlayer(player) { it.copy(graveyard = it.graveyard.adding(reborn)) }
                .emit(GameEvent.CardDiscarded(player, newId, libraryObject.card))
        }
    }

/**
 * Whether the pool card [obj] matches [filter] (CR 701.16, CR 701.14a) — read from its **printed**
 * characteristics, because the card is in a library and the CR 613 layer system does not reach one
 * (CR 109.3), so nothing on the battlefield can change what it matches.
 *
 * `internal` and shared with the private-look path (LibraryLook.kt): a filtered *look*
 * ([dev.mtgplay.core.definition.LibraryLookMode.RevealMatchingToHandRestToBottom]) asks the same question
 * of the same enum, and spelling the predicate twice is how two answers to "is this an instant or sorcery
 * card?" drift apart.
 */
internal fun matchesFilter(
    state: GameState,
    obj: GameObject,
    filter: RevealedCardFilter,
): Boolean {
    val printed = state.definitions[obj.card]?.characteristics
    val types = printed?.cardTypes.orEmpty()
    return when (filter) {
        RevealedCardFilter.PERMANENT_CARD -> types.any { it in PERMANENT_CARD_TYPES }
        RevealedCardFilter.ENCHANTMENT_CARD -> CardType.ENCHANTMENT in types
        // CR 105.2c, CR 202.2: colour comes from the printed mana cost, so a card with none is colorless.
        RevealedCardFilter.COLORLESS_CARD -> printed?.colors.orEmpty().isEmpty()
        RevealedCardFilter.INSTANT_OR_SORCERY_CARD -> CardType.INSTANT in types || CardType.SORCERY in types
        RevealedCardFilter.CREATURE_CARD -> CardType.CREATURE in types
        // CR 305.1: a land card. Winding Way's second half; a land *creature* satisfies both members,
        // which is right — "cards of the chosen type" reads a card's whole type set.
        RevealedCardFilter.LAND_CARD -> CardType.LAND in types
    }
}
