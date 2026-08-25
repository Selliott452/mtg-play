package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.LibraryLook
import dev.mtgplay.core.definition.LibraryLookSource
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.shuffled
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingLibraryLook
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.resolutionClauses
import dev.mtgplay.core.state.resolutionController
import dev.mtgplay.core.state.resolutionSourceCard
import dev.mtgplay.core.state.resolutionSourceId
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.effect.drawCards
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/*
 * The private look-and-arrange flow (CR 701.14, CR 701.17): look at some cards, then put them where you
 * want them — Preordain's scry 2, Ponder's reorder-the-top-three, Impulse's one-to-hand-rest-to-bottom, and
 * Brainstorm's two-from-hand-on-top. The sibling of the library *reveal* flow (LibraryReveal.kt) and
 * deliberately not a mode of it: a reveal is public and emits [GameEvent.CardsRevealed], a look is private
 * and emits only a count (docs/design/library-look.md §6).
 *
 * Like the reveal, the arrangement is a mid-resolution decision, so the resolving object stays on top of
 * the stack and the pause is a pure derivation of the state (ADR-004). "Object", not "spell": the clause is
 * carried by [dev.mtgplay.core.definition.ResolutionClauses], so a triggered ability looks through exactly
 * this flow (`FW-CLAUSEHOOK`, docs/design/resolution-clause-hook.md). Unlike the reveal, the *whole* decision
 * is enumerated up front: one [DecisionRequest.ChooseLibraryArrangement] whose options are every legal
 * complete arrangement of the pool (LibraryArrangements.kt), so legality is defined by the enumeration and
 * a mandatory keep simply has no decline index (ADR-005).
 *
 * A clause with an optional shuffle (Ponder's "You may shuffle.") pauses a second time for a
 * [DecisionRequest.ChooseYesNo]; the shuffle itself draws from the match-owned PRNG (ADR-006). Any trailing
 * draw ([LibraryLook.thenDraw]) happens last, after the arrangement and the shuffle, which is the printed
 * order of "Scry 2, then draw a card."
 */

/**
 * Runs a resolving object's library-look clause (CR 701.14a): takes the pool — the top
 * [LibraryLook].mode.count cards of the controller's library, or their hand — records the private look, and
 * pauses for the arrangement choice. The pool stays in its source zone during the pause, and nothing about
 * it is revealed.
 *
 * [entry] is any [StackEntry] (`FW-CLAUSEHOOK`): a resolving spell (Preordain), a resolving triggered
 * ability (Faerie Seer's enters-the-battlefield scry), or a resolving activated ability. The look itself is
 * identical in all three — only [completeClauseResolution] differs.
 *
 * Always pauses, even for a pool that admits exactly one arrangement (an empty library, or a forced
 * ordering): the engine never collapses a decision, the same rule that always surfaces a lone payment plan
 * (ADR-004).
 */
internal fun orchestrateLibraryLook(
    state: GameState,
    entry: StackEntry,
    look: LibraryLook,
): AdvanceResult {
    val decider = entry.resolutionController
    val pool = lookPool(state, decider, look)
    val announced = state.emit(GameEvent.CardsLookedAt(decider, pool.size))
    val paused =
        announced.copy(
            pendingLibraryLook = PendingLibraryLook(decider, pool.map { it.id }.toPersistentList()),
        )
    return AdvanceResult.NeedsDecision(paused, pendingLibraryLookRequest(paused))
}

/**
 * The arrangement request the open [GameState.pendingLibraryLook] is waiting on (CR 701.14a, CR 701.17a):
 * the pool, resolved to identities for the deciding seat only, plus every legal arrangement of it. A pure
 * function of the state (ADR-004) — the resolving spell (with its clause) is the top of the stack and the
 * pool is still in its source zone.
 */
internal fun pendingLibraryLookRequest(state: GameState): DecisionRequest.ChooseLibraryArrangement {
    val pending = state.pendingLibraryLook ?: error("no library look is pending")
    val look = lookClauseOf(state)
    val pool = pending.poolIds.map { id -> poolObject(state, pending.decider, id) }
    return DecisionRequest.ChooseLibraryArrangement(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        prompt = arrangementPrompt(look),
        pool = pool.map { DecisionRequest.ChooseLibraryArrangement.PoolCard(it.id, it.card) },
        options = arrangementsFor(look.mode, pool.size, matchingPoolIndices(state, look.mode, pool)),
    )
}

/**
 * Applies the chosen arrangement (CR 701.17a, CR 400.7): moves the pool to the hand, the top of the
 * library, and the bottom of the library as [option] says, then either pauses for the clause's optional
 * shuffle (CR 601.3b) or finishes the clause.
 */
internal fun applyLibraryArrangement(
    state: GameState,
    option: DecisionRequest.ChooseLibraryArrangement.Option,
): AdvanceResult {
    val pending = state.pendingLibraryLook ?: error("no library look is pending")
    val entry = resolvingClauseEntry(state)
    val look = lookClauseOf(state)
    val announced = announceRevealedKeeps(state, pending.decider, look.mode, pending.poolIds, option)
    // The pending record is cleared *first*: it asserts that every pool card is still in its source zone
    // (GameState.init), and the distribution takes them out of it one intermediate state at a time.
    val cleared = announced.copy(pendingLibraryLook = null)
    val arranged = distributeArrangement(cleared, pending.decider, look.mode.source, pending.poolIds, option)
    return if (look.optionalShuffle) {
        val paused =
            arranged.copy(pendingLibraryLook = PendingLibraryLook(pending.decider, persistentListOf(), true))
        AdvanceResult.NeedsDecision(paused, libraryLookShuffleRequest(paused))
    } else {
        finishLibraryLook(arranged, entry, look, pending.decider)
    }
}

/**
 * The "you may shuffle" request of a clause whose arrangement is settled (CR 601.3b — Ponder's). Pure per
 * ADR-004: the resolving object is still the top of the stack and the pending look records the stage. The
 * request points at that object's *source* (CR 113.7c LKI for an ability, the card on the stack for a
 * spell), which is what the deciding seat needs to know whose "you may" this is.
 */
internal fun libraryLookShuffleRequest(state: GameState): DecisionRequest.ChooseYesNo {
    val pending = state.pendingLibraryLook ?: error("no library look is pending")
    val entry = resolvingClauseEntry(state)
    return DecisionRequest.ChooseYesNo(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        prompt = "Shuffle your library?",
        cardObjectId = entry.resolutionSourceId,
        card = entry.resolutionSourceCard,
    )
}

/**
 * Applies the optional-shuffle answer (CR 601.3b): on accept the decider's library is shuffled through the
 * match PRNG (ADR-006 — the seeded draw makes replay reproduce the new order), discarding the ordering just
 * chosen, which is exactly what Ponder's "You may shuffle." means. Then the clause finishes.
 */
internal fun applyLibraryLookShuffle(
    state: GameState,
    accept: Boolean,
): AdvanceResult {
    val pending = state.pendingLibraryLook ?: error("no library look is pending")
    val entry = resolvingClauseEntry(state)
    val shuffled = if (accept) shuffleLibraryOf(state, pending.decider) else state
    return finishLibraryLook(shuffled, entry, lookClauseOf(state), pending.decider)
}

/**
 * Closes the clause: clears the pending look, performs the trailing draw (CR 121.1 — "…, then draw a
 * card"), and finishes the resolving object [entry] — a spell's CR 608.2m graveyard move or an ability's
 * CR 113.7a cessation, whichever [completeClauseResolution] says.
 */
private fun finishLibraryLook(
    state: GameState,
    entry: StackEntry,
    look: LibraryLook,
    decider: PlayerId,
): AdvanceResult {
    val cleared = state.copy(pendingLibraryLook = null)
    val drawn = if (look.thenDraw > 0) drawCards(cleared, decider, look.thenDraw) else cleared
    return completeClauseResolution(drawn, entry)
}

/** Shuffles [player]'s library through the match PRNG (CR 701.20, ADR-006); the seeded draw makes replay reproduce. */
private fun shuffleLibraryOf(
    state: GameState,
    player: PlayerId,
): GameState {
    val (shuffled, nextRng) = state.player(player).library.shuffled(state.rng)
    return state.copy(rng = nextRng).updatePlayer(player) { it.copy(library = shuffled) }
}

/**
 * The pool a clause looks at (CR 701.14a): the top `count` cards of the decider's library, or — for a
 * hand-sourced clause — their whole hand, from which `count` are placed. A short zone yields a short pool,
 * which is the CR's "do as much as possible".
 */
private fun lookPool(
    state: GameState,
    decider: PlayerId,
    look: LibraryLook,
): List<GameObject> =
    when (look.mode.source) {
        LibraryLookSource.TOP_OF_LIBRARY -> state.player(decider).library.take(look.mode.count)
        LibraryLookSource.HAND -> state.player(decider).hand.toList()
    }

/** The [LibraryLook] clause of the object resolving on top of the stack; fails loudly rather than guessing. */
private fun lookClauseOf(state: GameState): LibraryLook =
    resolvingClauseEntry(state).resolutionClauses.libraryLook
        ?: error("CR 701.14a: a library look requires a resolving object with a look clause on the stack")
