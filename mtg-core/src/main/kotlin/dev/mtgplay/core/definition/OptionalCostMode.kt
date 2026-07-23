package dev.mtgplay.core.definition

/**
 * One mode of an optional "you may [pay a cost]; if you do, draw" resolution clause (CR 601.3b) — the small
 * sealed vocabulary of the cost a player may choose to pay. Additive, flagged core (P6.2c). Highway Robbery's
 * "you may discard a card or sacrifice a land" offers both members; a clause offering a single mode (were one
 * printed) would list only that member. `mtg-rules` owns which modes are performable, gathers the chosen
 * mode's object selection, pays it, and draws.
 *
 * A sealed interface (not an enum) so a `when` over the modes stays exhaustive and a future mode carrying data
 * (a typed permanent to sacrifice) can be added without reshaping the existing members.
 */
sealed interface OptionalCostMode {
    /**
     * Discard a card from the deciding player's hand (CR 701.8). The discard routes through the CR 614/616
     * framework, so a discarded madness card is exiled instead of going to the graveyard (CR 702.35a).
     */
    data object DiscardCard : OptionalCostMode

    /** Sacrifice a land the deciding player controls (CR 701.17). */
    data object SacrificeLand : OptionalCostMode
}
