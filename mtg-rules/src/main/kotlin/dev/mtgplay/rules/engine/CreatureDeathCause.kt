package dev.mtgplay.rules.engine

/**
 * Why a creature died as a state-based action — the two CR 704.5 members that put a creature into
 * its owner's graveyard. Both perform the same *move* in P3.2 (regeneration and other
 * death-replacement effects are Phase 5); the distinction is carried so those effects, and any
 * observer that needs it, can tell destruction from a non-destruction graveyard-move.
 */
internal enum class CreatureDeathCause {
    /**
     * CR 704.5g: a creature with toughness greater than 0 has marked damage at least equal to its
     * toughness — it has been dealt lethal damage and is **destroyed**. Regeneration (CR 701.15,
     * Phase 5) can replace this destruction event.
     */
    LETHAL_DAMAGE,

    /**
     * CR 704.5f: a creature with toughness 0 or less is put into its owner's graveyard. This is
     * **not** destruction — regeneration cannot replace it, and no "can't be destroyed" effect
     * (indestructible, Phase 5) prevents it. Distinct from [LETHAL_DAMAGE] for exactly that reason.
     * Unreachable end-to-end in the P3.2 pool (no effect lowers printed toughness until the layer
     * system, Phase 4); modelled now so the toughness check is complete and correctly prioritised
     * over CR 704.5g (which requires toughness greater than 0).
     */
    ZERO_OR_LESS_TOUGHNESS,
}
