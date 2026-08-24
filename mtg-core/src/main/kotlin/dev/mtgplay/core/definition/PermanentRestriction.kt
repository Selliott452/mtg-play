package dev.mtgplay.core.definition

/**
 * Which battlefield permanents a [TargetSpec.TargetPermanent] may choose from (CR 115.1b) — the
 * noun half of "target nonlegendary creature", "target artifact", "target permanent". Additive,
 * flagged core (the removal-and-destruction packet).
 *
 * **Core/rules split (ADR-009).** This is the *declaration* of what a card's targeting line says;
 * `mtg-rules` owns deciding whether a given battlefield object satisfies it, which is why a
 * restriction that reads a *computed* characteristic ([CREATURE_POWER_2_OR_LESS]) is expressible
 * here without core knowing anything about the CR 613 layer system.
 *
 * A closed enum rather than a predicate for the same reason [EnchantRestriction] is one: a card
 * definition is data (ADR-003), the enumerator and the CR 608.2b re-check must agree by
 * construction, and a new restriction must break the rules-side `when` rather than slip through.
 * Members exist only where a card in the pool prints them.
 */
enum class PermanentRestriction {
    /** "Target permanent" (CR 115.1b): any permanent on the battlefield. Scour from Existence. */
    ANY_PERMANENT,

    /** "Target creature" (CR 302): any creature on the battlefield. Terminate. */
    CREATURE,

    /**
     * "Target nonlegendary creature" (CR 302, CR 205.4): a creature whose printed supertypes do not
     * include legendary. Cast Down. The MVP pool prints no legendary card at all, so the exclusion
     * is currently vacuous in play — it is modelled anyway so the printed line is not quietly wrong,
     * and it is tested against a legendary fixture in `mtg-rules`.
     */
    NONLEGENDARY_CREATURE,

    /**
     * "Target creature with power 2 or less" (CR 115.1b, CR 208.1). Last Breath. The power read is
     * the creature's **in-game** power (CR 613 sublayer 7c), not its printed one, so a creature
     * pumped in response to the spell stops being a legal target and the spell fizzles at the
     * CR 608.2b re-check.
     */
    CREATURE_POWER_2_OR_LESS,

    /** "Target artifact" (CR 301): any artifact on the battlefield, including an artifact land.
     * Smash to Smithereens, Ancient Grudge. */
    ARTIFACT,
}
