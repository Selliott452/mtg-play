package dev.mtgplay.core.definition

/**
 * What an Aura's enchant ability may legally be attached to (CR 303.4, CR 702.5) — the target
 * restriction of a [TargetSpec.Enchantable]. Additive, flagged core (P4.1).
 *
 * The MVP-minimal set: exactly the enchant restrictions the pinned pool prints
 * (docs/decklists.md). Modelled as nouns — printed-characteristic predicates — that `mtg-rules`
 * interprets against a candidate object (reading printed types/subtypes, no type-changing effect
 * exists) plus control; core states *which* restriction, rules decides *whether* an object
 * satisfies it. Sealed as an enum so the rules interpreter's `when` is exhaustive and a new
 * restriction breaks compilation rather than being silently mis-enforced.
 *
 * "Control" is ownership for now (docs/design/layer-system.md §4): no control-changing effect
 * exists, so "a creature you control" is "a creature you own" — the seam is noted for when layer 2
 * gains an effect.
 */
enum class EnchantRestriction {
    /** Enchant creature (CR 303.4a): any creature (CR 302.1). */
    CREATURE,

    /** Enchant land (CR 303.4a): any land (CR 305.1). */
    LAND,

    /** Enchant Forest (CR 303.4a): a land with the Forest subtype (CR 205.3, Utopia Sprawl). */
    FOREST,

    /**
     * Enchant creature you control (CR 303.4a): a creature whose controller is the Aura's
     * controller. Control is ownership in the MVP pool (docs/design/layer-system.md §4).
     */
    CREATURE_YOU_CONTROL,
}
