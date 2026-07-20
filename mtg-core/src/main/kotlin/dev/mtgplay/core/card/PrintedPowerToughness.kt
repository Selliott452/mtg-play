package dev.mtgplay.core.card

/**
 * A creature card's printed power and toughness (CR 208.1).
 *
 * Plain integers: nothing in the MVP pool has a `*` or otherwise characteristic-defined P/T
 * box, so those fail at the modeling boundary instead of being approximated. In-game P/T
 * modification is computed by the layer system (CR 613) in Phase 4; printed values are never
 * edited. Values are unconstrained — printed zero and even negative values exist.
 *
 * @property power the printed power.
 * @property toughness the printed toughness.
 */
data class PrintedPowerToughness(
    val power: Int,
    val toughness: Int,
)
