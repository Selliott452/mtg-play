package dev.mtgplay.core.definition

/**
 * Which objects a [StaticContinuousEffect] applies to (CR 611.2c) — the "affected set" a static
 * ability's continuous effect modifies. Additive, flagged core (P4.1).
 *
 * Sealed so `mtg-rules` resolves the set exhaustively and a new selector breaks compilation
 * rather than being silently ignored. The MVP pool exercises exactly one member: [Enchanted], the
 * single object an Aura is attached to. `Self` and computed-set selectors ("other creatures you
 * control") are the sealed extension point and are not built (docs/design/layer-system.md §2).
 */
sealed interface AffectedSet {
    /**
     * The one object the generating Aura is attached to (CR 303.4, CR 611.2c) — the affected set
     * of every MVP continuous effect. Empty when the Aura is attached to nothing, in which case
     * the Aura is falling off as a state-based action (CR 704.5m) and its effect is inactive.
     */
    data object Enchanted : AffectedSet
}
