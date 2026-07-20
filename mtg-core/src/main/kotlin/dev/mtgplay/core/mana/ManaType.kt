package dev.mtgplay.core.mana

/**
 * A type of mana as it exists in a mana pool: one of the five colors, or colorless (CR 106.1b).
 *
 * This is *produced* mana — what a resolved mana ability adds to a pool — as distinct from the
 * cost symbols in [ManaSymbol]: the {C} cost symbol demands specifically colorless mana
 * (CR 107.4c), while generic {N} is payable by mana of any type. Spend restrictions and riders
 * ("Spend this mana only to cast…", CR 106.6) are deliberately not modeled: nothing in the MVP
 * card pool produces restricted mana.
 */
enum class ManaType {
    /** White mana (CR 106.1b). */
    WHITE,

    /** Blue mana (CR 106.1b). */
    BLUE,

    /** Black mana (CR 106.1b). */
    BLACK,

    /** Red mana (CR 106.1b). */
    RED,

    /** Green mana (CR 106.1b). */
    GREEN,

    /**
     * Colorless mana (CR 106.1b): what a {C} cost symbol demands (CR 107.4c) and what, e.g.,
     * an Eldrazi Spawn's sacrifice ability produces.
     */
    COLORLESS,
}
