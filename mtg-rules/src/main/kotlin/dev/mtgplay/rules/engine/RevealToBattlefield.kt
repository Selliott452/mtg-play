package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.LibraryReveal
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.ContinuousModification
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.resolutionSourceCard
import dev.mtgplay.rules.effect.applyUntilYourNextTurn
import kotlinx.collections.immutable.PersistentMap

/*
 * The battlefield end of a CR 701.16 reveal
 * ([dev.mtgplay.core.definition.RevealDisposition.CHOSEN_TO_BATTLEFIELD_REST_SHUFFLED]) — Throne of
 * the Dead Three, *"Reveal the top ten cards of your library. Put a creature card from among them onto
 * the battlefield with three +1/+1 counters on it. It gains hexproof until your next turn. Then
 * shuffle."* Added by `W11`.
 *
 * Its own file rather than a third distribution function in LibraryReveal.kt, which is at detekt's
 * per-file budget and is about the *selection*; this is about what a chosen card becomes.
 *
 * **Every piece here already existed somewhere else, which is why this is short.** The entry is
 * [announceBattlefieldEntry], the one home every entry path shares (triage T18); the tapped status is
 * [entersTappedNow]; the counters are CR 614.1c read through [countersFrom]; the keyword grant is the
 * published [applyUntilYourNextTurn]; and the shuffle is [shuffleLibrary], the same one the CR 701.18
 * search uses, so the seeded entropy a "then shuffle" consumes is drawn one way (ADR-006). What was
 * genuinely missing was a reveal that ends on the battlefield and leaves the rest of the pool where it
 * lay — every reveal before this one emptied the revealed cards into a hand and a graveyard.
 */

/**
 * Distributes a finished reveal whose chosen cards go **onto the battlefield** (CR 701.16, CR 400.7):
 * each id in [kept] leaves [player]'s library and enters under their control, carrying the clause's
 * CR 614.1c counters and gaining its CR 611.2 keywords until [player]'s next turn; every other revealed
 * card stays in the library, which is then shuffled.
 *
 * **The unchosen cards are not moved, and that is the printed card rather than an optimisation.**
 * Throne of the Dead Three names no destination for them because they never left: "Reveal the top ten
 * cards" shows them where they are (CR 701.16a). The shuffle is the instruction that matters — without
 * it the revealer would know their next nine draws — and it happens whether or not anything was chosen,
 * which is why it is applied to the state after the fold rather than inside it.
 */
internal fun putRevealedOntoBattlefield(
    state: GameState,
    entry: StackEntry,
    player: PlayerId,
    kept: Set<ObjectId>,
    reveal: LibraryReveal,
): GameState {
    val entered = kept.fold(state) { current, id -> putRevealedCardOntoBattlefield(current, entry, player, id, reveal) }
    return shuffleLibrary(entered, player)
}

/**
 * Puts one chosen revealed card onto the battlefield as a **new** object (CR 400.7, CR 614.1c) and
 * grants it the clause's keywords until [player]'s next turn (CR 611.2).
 *
 * The counters are placed by **construction**, not by [dev.mtgplay.rules.effect.putCounters] after the
 * fact, and CR 614.1c is why: an enters-with-counters replacement modifies the entering event itself,
 * so the permanent is never on the battlefield without them. A three-counter creature that arrived as a
 * counterless body and grew a moment later would be a different card — an enters-the-battlefield
 * trigger reading its power would read the wrong number, and a 0/0 body would already have died to the
 * CR 704.5f state-based action.
 *
 * The keyword grant is applied **after** the entry, and must be: CR 611.2c fixes a continuous effect's
 * affected object when the effect begins, so there has to be an object. That ordering is also the
 * card's — "Put a creature card … onto the battlefield … It gains hexproof" — and it is safe because
 * the CR 603.6a triggers [announceBattlefieldEntry] fires are only *enqueued*, so nothing has resolved
 * that could have moved the permanent before the grant lands.
 */
private fun putRevealedCardOntoBattlefield(
    state: GameState,
    entry: StackEntry,
    player: PlayerId,
    objectId: ObjectId,
    reveal: LibraryReveal,
): GameState {
    val library = state.player(player).library
    val index = library.indexOfFirst { it.id == objectId }
    require(index >= 0) { "CR 701.16: the chosen revealed card $objectId is no longer in $player's library" }
    val chosen = library[index]
    val removed = state.updatePlayer(player) { it.copy(library = it.library.removingAt(index)) }
    val (battlefieldId, allocated) = removed.allocateObjectId()
    val definition = allocated.definitions[chosen.card]
    val entering =
        GameObject(
            id = battlefieldId,
            card = chosen.card,
            owner = chosen.owner,
            // CR 110.5a: untapped unless the permanent's own CR 614.1c clause replaces it. No reveal in
            // the pool says "tapped", so the only source is the entering card itself — read before the
            // object joins the battlefield, as every other entry path reads it.
            tapped = entersTappedNow(allocated, player, definition),
            counters = enteringCounters(allocated, chosen.card, reveal),
        )
    val onBattlefield = allocated.updateBattlefield { it.adding(entering) }
    val announced =
        announceBattlefieldEntry(
            onBattlefield,
            battlefieldId,
            GameEvent.PermanentEntered(player, objectId, chosen.card, battlefieldId),
        )
    return if (reveal.grantedUntilYourNextTurn.isEmpty()) {
        announced
    } else {
        applyUntilYourNextTurn(
            state = announced,
            affected = battlefieldId,
            player = player,
            modification = ContinuousModification(grantedKeywords = reveal.grantedUntilYourNextTurn),
            sourceCard = entry.resolutionSourceCard,
        )
    }
}

/**
 * The counters an entering card carries (CR 614.1c) — the **sum** of the two sources the rule covers:
 * the permanent's own printed "this enters with N counters" clause, and the counters named by the
 * effect putting it there.
 *
 * Both are CR 614.1c replacements of the same event, so both apply; taking the larger, or letting
 * either win, would be a rule this engine invented. `chosenX` is zero because a card put onto the
 * battlefield from a library was never cast and so has no announcement to read (CR 107.3b), which is
 * the same zero the play-land path passes.
 */
private fun enteringCounters(
    state: GameState,
    card: CardRef,
    reveal: LibraryReveal,
): PersistentMap<Counter, Int> {
    val own = entersWithCountersNow(state.definitions[card], 0)
    return countersFrom(reveal.entersWithCounters, 0).entries.fold(own) { counters, (kind, amount) ->
        counters.putting(kind, (counters[kind] ?: 0) + amount)
    }
}
