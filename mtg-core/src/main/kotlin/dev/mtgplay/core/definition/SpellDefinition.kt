package dev.mtgplay.core.definition

import dev.mtgplay.core.mana.ManaCost

/**
 * The [CardDefinition] refinement for a castable card: everything the CR 601 casting pipeline
 * needs that a plain definition does not carry.
 *
 * Lands are the deliberate non-member: a land is *played*, not cast (CR 115.2a, CR 305.1), so
 * a basic-land definition implements only [CardDefinition] and the play-land action (P2.2)
 * never touches this type. The casting pipeline requires a [SpellDefinition] and a printed
 * mana cost; additional and alternative costs (Grab the Prize, Fireblast — Phase 5,
 * docs/decklists.md) will extend the *pipeline's* cost-determination hook, not this contract.
 */
interface SpellDefinition : CardDefinition {
    /** When this spell may be cast (CR 117.1a). */
    val timing: TimingClass

    /** What this spell demands as targets (CR 115); [TargetSpec.None] for an untargeted spell. */
    val targetSpec: TargetSpec

    /** The spell's resolution instructions (CR 608.2c). */
    val resolution: ResolutionEffect

    /**
     * The printed mana cost (CR 202). Non-null by contract in P2.1: every castable fixture has
     * one, and cost determination (CR 601.2f) fails loudly on a spell without a mana cost until
     * the alternative-cost hook (Phase 5) gives "no mana cost" a meaning.
     */
    val manaCost: ManaCost? get() = characteristics.manaCost
}
