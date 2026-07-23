package dev.mtgplay.pauper

import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.identity.CardRef

/**
 * Checks that a legal deck is also *playable*: every mainboard card resolves to a rules
 * [CardDefinition] in a provided registry (P6.1).
 *
 * This is distinct from legality (a card can be perfectly legal yet not-yet-encoded): the coverage
 * report's [CoverageReport.missing] is the exact list of mainboard cards the engine cannot yet play
 * because `mtg-cards` has no definition for them. In P6.1 both MVP decks have known gaps; that gap
 * list is P6.2's checklist and shrinks consciously as P6.2 encodes cards (a test pins the current
 * set). Only the mainboard is checked — the sideboard never enters the single-game MVP.
 *
 * A [CardRef] without a definition is inert but legal in the engine (it shuffles, draws, and
 * discards) — see `CardDefinition` — so a gap does not stop a game from starting; it only bounds
 * what that game can do.
 */
object DefinitionCoverage {
    /**
     * The definitions missing for [deck]'s mainboard, checked against [definitions] (the MVP
     * registry by default). The gap list is the mainboard cards with no registry entry, in
     * first-appearance order.
     */
    fun check(
        deck: LoadedDeck,
        definitions: Map<CardRef, CardDefinition> = MvpCards.definitions,
    ): CoverageReport {
        val missing = deck.distinctMainRefs().filter { it !in definitions }
        return CoverageReport(deckName = deck.name, missing = missing)
    }
}

/**
 * The outcome of a definition-coverage check (P6.1): which mainboard cards are not yet playable.
 *
 * @property deckName the checked deck's name.
 * @property missing the distinct mainboard [CardRef]s with no [CardDefinition], in first-appearance
 *   order; empty when the mainboard is fully playable.
 */
data class CoverageReport(
    val deckName: String,
    val missing: List<CardRef>,
) {
    /** Whether every mainboard card is playable (no missing definitions). */
    val isPlayable: Boolean get() = missing.isEmpty()

    /** The missing cards' names, sorted — the stable form a test pins and a report prints. */
    val missingNames: List<String> get() = missing.map { it.name }.sorted()
}
