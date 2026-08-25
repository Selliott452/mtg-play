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

/**
 * The [ManaType] one mana of this colour is (CR 106.1b) — the total mapping from the five colours onto
 * their five coloured mana types, with [ManaType.COLORLESS] deliberately unreachable because colorless is
 * the absence of colour rather than a sixth one (CR 105.4).
 *
 * In `mtg-core` because both sides need it and neither owns it: a card definition names a *colour* (the
 * Gate cycle's "Add {W} or one mana of the chosen color", whose choice is a [Color]) while a mana pool
 * holds a [ManaType], and the payment machinery crosses the same boundary at every coloured symbol. It is
 * a total function over a closed enum and decides nothing, so it is a noun by the PLAN.md §3 rule.
 */
fun Color.manaType(): ManaType =
    when (this) {
        Color.WHITE -> ManaType.WHITE
        Color.BLUE -> ManaType.BLUE
        Color.BLACK -> ManaType.BLACK
        Color.RED -> ManaType.RED
        Color.GREEN -> ManaType.GREEN
    }
