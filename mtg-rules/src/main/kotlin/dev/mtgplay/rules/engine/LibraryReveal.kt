package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.definition.LibraryReveal
import dev.mtgplay.core.definition.RevealedCardFilter
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingRevealSelection
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import kotlinx.collections.immutable.toPersistentList

/*
 * Library manipulation (CR 701.16): reveal the top N cards, put up to one matching card into the hand,
 * and put the rest into the graveyard — Malevolent Rumble. The keep-one choice is a mid-resolution
 * decision, so the engine orchestrates the reveal around the pure resolution effect: the top N cards are
 * revealed (public information, [GameEvent.CardsRevealed]) and, if any match the filter, the resolving
 * spell's controller is asked which to keep before the cards are distributed. When nothing matches, all
 * revealed cards go straight to the graveyard with no pause.
 */

/** The set of card types that make a card a permanent card (CR 110.4a) — anything but instant and sorcery. */
private val PERMANENT_CARD_TYPES: Set<CardType> =
    setOf(CardType.ARTIFACT, CardType.CREATURE, CardType.ENCHANTMENT, CardType.LAND)

/**
 * Reveals the top [reveal].count cards of [entry]'s controller's library (CR 701.16), emitting
 * [GameEvent.CardsRevealed], then either pauses for the keep-one choice (when at least one revealed card
 * matches [reveal].toHand) or, with no match, puts every revealed card into the graveyard and finishes
 * the spell. The revealed cards stay on top of the library during the pause.
 */
internal fun orchestrateLibraryReveal(
    state: GameState,
    entry: StackEntry.Spell,
    reveal: LibraryReveal,
): AdvanceResult {
    val controller = entry.controller
    val revealed = state.player(controller).library.take(reveal.count)
    val announced =
        if (revealed.isEmpty()) state else state.emit(GameEvent.CardsRevealed(controller, revealed.map { it.card }))
    val candidates = revealed.filter { matchesFilter(state, it, reveal.toHand) }
    return if (candidates.isEmpty()) {
        // CR 701.16: nothing to keep — every revealed card goes to the graveyard, then the spell leaves.
        val distributed = putRevealedIntoGraveyard(announced, controller, revealed.map { it.id }, keep = null)
        completeInstantSorceryResolution(distributed, entry)
    } else {
        val paused =
            announced.copy(
                pendingRevealSelection = PendingRevealSelection(controller, revealed.map { it.id }.toPersistentList()),
            )
        AdvanceResult.NeedsDecision(paused, pendingRevealRequest(paused))
    }
}

/**
 * The keep-one request the open [GameState.pendingRevealSelection] is waiting on (CR 701.16): the
 * revealed cards matching the resolving spell's filter, plus a "keep none" option. A pure function of
 * the state (ADR-004) — the resolving spell (with its filter) is the top of the stack and the revealed
 * cards are still the top of the library.
 */
internal fun pendingRevealRequest(state: GameState): DecisionRequest.ChooseFromRevealed {
    val pending = state.pendingRevealSelection ?: error("no reveal selection is pending")
    val reveal =
        (state.sharedZones.stack.lastOrNull() as? StackEntry.Spell)?.definition?.libraryReveal
            ?: error("CR 701.16: a reveal selection requires a resolving spell with a reveal clause on the stack")
    val library = state.player(pending.decider).library
    val options =
        pending.revealedIds
            .mapNotNull { id -> library.firstOrNull { it.id == id } }
            .filter { matchesFilter(state, it, reveal.toHand) }
            .map { DecisionRequest.ChooseFromRevealed.Option(it.id, it.card) }
    return DecisionRequest.ChooseFromRevealed(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        options = options,
    )
}

/**
 * Applies the keep-one choice (CR 701.16): puts [keptObjectId] (or none) into the decider's hand and the
 * rest of the revealed cards into their graveyard, then finishes the resolving spell. Called with the
 * revealed library object id to keep, or `null` to keep none.
 */
internal fun applyRevealSelection(
    state: GameState,
    keptObjectId: ObjectId?,
): AdvanceResult {
    val pending = state.pendingRevealSelection ?: error("no reveal selection is pending")
    val entry =
        state.sharedZones.stack.lastOrNull() as? StackEntry.Spell
            ?: error("CR 701.16: a reveal selection requires a resolving spell on top of the stack")
    val cleared = state.copy(pendingRevealSelection = null)
    val distributed = putRevealedIntoGraveyard(cleared, pending.decider, pending.revealedIds, keep = keptObjectId)
    return completeInstantSorceryResolution(distributed, entry)
}

/**
 * Moves the revealed library cards [revealedIds] out of the [player]'s library (CR 400.7): [keep] (if
 * non-null) to the hand, the rest to the graveyard in reveal order. Each becomes a new object. Emits a
 * [GameEvent.CardReturnedToHand] for the kept card (reused as the generic move-to-hand event) and a
 * [GameEvent.CardDiscarded] for each to the graveyard (reused as the generic move-to-graveyard event).
 */
private fun putRevealedIntoGraveyard(
    state: GameState,
    player: dev.mtgplay.core.identity.PlayerId,
    revealedIds: List<ObjectId>,
    keep: ObjectId?,
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
        if (id == keep) {
            withoutCard
                .updatePlayer(player) { it.copy(hand = it.hand.adding(reborn)) }
                .emit(GameEvent.CardReturnedToHand(player, newId, libraryObject.card))
        } else {
            withoutCard
                .updatePlayer(player) { it.copy(graveyard = it.graveyard.adding(reborn)) }
                .emit(GameEvent.CardDiscarded(player, newId, libraryObject.card))
        }
    }

/** Whether the revealed [obj] matches the reveal [filter] (CR 701.16) — read from its printed types. */
private fun matchesFilter(
    state: GameState,
    obj: GameObject,
    filter: RevealedCardFilter,
): Boolean =
    when (filter) {
        RevealedCardFilter.PERMANENT_CARD -> {
            val types =
                state.definitions[obj.card]
                    ?.characteristics
                    ?.cardTypes
                    .orEmpty()
            types.any { it in PERMANENT_CARD_TYPES }
        }
    }
