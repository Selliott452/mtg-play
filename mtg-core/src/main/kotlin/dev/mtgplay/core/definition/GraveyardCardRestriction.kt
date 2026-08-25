package dev.mtgplay.core.definition

/**
 * Which graveyard cards a [TargetSpec.CardInGraveyard] may choose from (CR 115.1, CR 404) — the noun
 * half of "target *instant or sorcery* card from your graveyard", "target *creature or land* card from
 * a graveyard". Additive, flagged core (`FW-ZONETGT`, docs/design/graveyard-targeting.md §4).
 *
 * **Core/rules split (ADR-009).** This is the *declaration* of what a card's targeting line says;
 * `mtg-rules` owns deciding whether a given graveyard object satisfies it, reading the card's types
 * through its own accessor. The same split [PermanentRestriction] and [SpellRestriction] already make.
 *
 * A closed enum rather than a predicate, for the reason [PermanentRestriction] is one: a card definition
 * is data (ADR-003), the enumerator and the CR 608.2b re-check must agree by construction, and a new
 * restriction must break the rules-side `when` rather than slip through. Members exist only where a card
 * in the pool prints them — so the two "X or Y" shapes below are here and the plain nouns the rest of
 * the family prints ("target creature card", "target card") are not, because every card that prints
 * them is blocked on a framework this packet does not own (docs/design/graveyard-targeting.md §6).
 *
 * **A graveyard card has no in-game characteristics to read.** Unlike [PermanentRestriction], which
 * deliberately admits a *computed* characteristic (`CREATURE_POWER_2_OR_LESS` reads layered power), the
 * CR 613 layer system does not apply in a graveyard (CR 613.1 governs objects it applies to; a card in a
 * graveyard has only its printed characteristics, CR 109.3). So every member here reads printed card
 * types and always will, and a restriction over a computed value would be a category error rather than a
 * missing feature.
 */
enum class GraveyardCardRestriction {
    /**
     * "Target instant or sorcery card" (CR 205.2): a card whose printed types include instant or
     * sorcery. Archaeomancer.
     */
    INSTANT_OR_SORCERY,

    /**
     * "Target creature or land card" (CR 205.2): a card whose printed types include creature or land.
     * Pulse of Murasa. An artifact *creature* card qualifies, because a card has a set of types rather
     * than one.
     */
    CREATURE_OR_LAND,
}
