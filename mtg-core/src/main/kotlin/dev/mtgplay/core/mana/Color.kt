package dev.mtgplay.core.mana

/**
 * The five colors of Magic (CR 105.1), declared in the canonical order white, blue, black,
 * red, green.
 *
 * Enum order is the canonical "WUBRG" order, so any collection built by filtering [entries]
 * iterates in a deterministic, rules-canonical order — stable option enumeration (ADR-005) and
 * replay (ADR-006) both rely on that. Colorless is deliberately not a member: per CR 105.4 it
 * is the absence of color, not a sixth color (colorless *mana* is [ManaType.COLORLESS]).
 *
 * @property letter the single-letter symbol used in Scryfall mana-cost syntax, e.g. `W` in `{W}`.
 */
enum class Color(
    val letter: Char,
) {
    /** White (CR 105.1). */
    WHITE('W'),

    /** Blue (CR 105.1). */
    BLUE('U'),

    /** Black (CR 105.1). */
    BLACK('B'),

    /** Red (CR 105.1). */
    RED('R'),

    /** Green (CR 105.1). */
    GREEN('G'),
}
