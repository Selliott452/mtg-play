package dev.mtgplay.pauper

import dev.mtgplay.core.mana.Color

/**
 * The trimmed printed metadata of one card, as ingested from the Scryfall snapshot (P6.1).
 *
 * This is the format layer's *data about* a printed card (a noun, PLAN.md §3): the fields deck
 * construction and the CLI need, kept as the raw printed strings Scryfall publishes rather than
 * re-parsed into the engine's mana/type model. The rules engine's own [CardMetadata]-free
 * vocabulary (mana costs, card types) is derived separately by `mtg-cards`; this record is the
 * bridge from a card *name* on a decklist to its printed facts and its Pauper legality.
 *
 * @property name the exact printed (oracle) card name (CR 201); the decklist-resolution key.
 * @property manaCost the printed mana cost string exactly as Scryfall gives it (CR 202), e.g.
 *   `{1}{R}` or `{G/U}`; empty for a card with no mana cost (a land).
 * @property typeLine the printed type line (CR 205), e.g. `Basic Land — Mountain`.
 * @property oracleText the printed rules text (CR 207); may be empty (a vanilla creature).
 * @property power the printed power exactly as printed (CR 208), or `null` for a non-creature;
 *   kept as a string because Scryfall prints `*`-style characteristic-defining values (none in
 *   the MVP pool, but the model must not lose them).
 * @property toughness the printed toughness (CR 208), or `null` for a non-creature.
 * @property colors the card's colors (CR 105), derived from Scryfall's `colors` array.
 * @property pauperLegality the card's Pauper legality (P6.1): the authoritative value the deck
 *   validator reads.
 * @property oracleId the Scryfall oracle id (CR 201-adjacent identity), the stable cross-printing
 *   identity of the card; carried through so a later [dev.mtgplay.core.identity.CardRef] can gain
 *   an oracle-id form (CardRef KDoc).
 */
data class CardMetadata(
    val name: String,
    val manaCost: String,
    val typeLine: String,
    val oracleText: String,
    val power: String?,
    val toughness: String?,
    val colors: Set<Color>,
    val pauperLegality: Legality,
    val oracleId: String,
) {
    init {
        require(name.isNotBlank()) { "card metadata name must not be blank" }
        require(oracleId.isNotBlank()) { "card \"$name\" has a blank oracle id" }
    }

    /**
     * Whether this card has the Basic supertype (CR 205.4) — the copy-limit exemption in deck
     * construction (CR 100.2a: any number of basic lands). Read from the type line's left side
     * (the supertypes and card types before the subtype dash), so it needs no separate field.
     */
    val isBasic: Boolean get() = BASIC_SUPERTYPE in leftTypeTokens()

    /**
     * The tokens on the left of the type line — the supertypes and card types before the `—`
     * subtype separator (CR 205.1a). `Basic Land — Mountain` yields `[Basic, Land]`.
     */
    private fun leftTypeTokens(): List<String> =
        typeLine
            .substringBefore(SUBTYPE_SEPARATOR)
            .trim()
            .split(' ')
            .filter { it.isNotBlank() }

    private companion object {
        /** The em dash Scryfall uses to separate types from subtypes on a type line (CR 205.1a). */
        const val SUBTYPE_SEPARATOR: String = "—"

        /** The Basic supertype token as it appears on a type line (CR 205.4). */
        const val BASIC_SUPERTYPE: String = "Basic"
    }
}
