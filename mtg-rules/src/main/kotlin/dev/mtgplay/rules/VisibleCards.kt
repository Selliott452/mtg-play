package dev.mtgplay.rules

import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.identity.CardRef

/*
 * The [SeatView.cards] derivation (ADR-007): which printed identities a finished view already names,
 * and their public characteristics. Kept beside [viewFor] rather than inside it because the scope
 * rule — "exactly the refs this view names, and no others" — is the whole security argument
 * (docs/design/seat-view-definitions.md §2) and deserves to be readable on its own.
 */

/**
 * The printed identities this view's zones name, and nothing else: the shared public zones, both
 * seats' graveyards (CR 404, public), the fired triggers' and stack entries' sources, the revealed
 * cards (CR 701.16), and the viewer's own hand — an opponent's hand is a [HandView.Concealed] count
 * that holds no objects, so it can contribute nothing (ADR-007). Libraries are counts and contribute
 * nothing on either side.
 *
 * Taking the already-filtered [view] as input rather than the raw
 * [dev.mtgplay.core.state.GameState] is deliberate: a hidden card can only reach this set by first
 * reaching the view, so the card table cannot leak what the view itself does not.
 */
internal fun visibleCardRefs(view: SeatView): Set<CardRef> =
    buildSet {
        view.battlefield.forEach { add(it.card) }
        view.exile.forEach { add(it.card) }
        view.stack.forEach { add(stackEntryCard(it)) }
        view.pendingTriggers.forEach { add(it.sourceCard) }
        view.pendingReveal?.revealed?.forEach { add(it.card) }
        view.players.forEach { player ->
            player.graveyard.forEach { add(it.card) }
            addAll(handCardRefs(player.hand))
        }
    }

/**
 * The public characteristics of each of [refs] that [definitions] defines, in canonical card-name
 * order so a view's serialized form is byte-stable for a given state.
 *
 * A ref with **no** definition is an inert card (the P2.1 ruling on [CardDefinition]) and is simply
 * absent from the result — the engine never fabricates characteristics for it.
 */
internal fun cardsOf(
    definitions: Map<CardRef, CardDefinition>,
    refs: Set<CardRef>,
): Map<CardRef, PrintedCardView> =
    refs
        .sortedBy { it.name }
        .mapNotNull { ref -> definitions[ref]?.let { ref to printedCardViewOf(it) } }
        .toMap()

/** The printed identity a stack entry names (CR 405): a spell's card, or an ability's source. */
private fun stackEntryCard(entry: StackEntryView): CardRef =
    when (entry) {
        is StackEntryView.SpellOnStack -> entry.card
        is StackEntryView.TriggeredAbilityOnStack -> entry.sourceCard
        is StackEntryView.ActivatedAbilityOnStack -> entry.sourceCard
    }

/** The identities a hand contributes: its contents when it is the viewer's own, none otherwise (CR 402). */
private fun handCardRefs(hand: HandView): List<CardRef> =
    when (hand) {
        is HandView.Revealed -> hand.cards.map { it.card }
        is HandView.Concealed -> emptyList()
    }
