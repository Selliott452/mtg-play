package dev.mtgplay.core.definition

/**
 * What a spell demands as targets (CR 115): the declarative spec a [SpellDefinition] states and
 * the engine enumerates legal choices for (ADR-005 — the engine enumerates, the definition only
 * describes).
 *
 * Sealed so the enumerator and the CR 608.2b resolution re-check handle every shape
 * exhaustively. P2.1 ships the two shapes the fixture pool needs; the model extends to further
 * members (target creature, target land, multi-target) without reshaping — a new member is a
 * new subtype plus its enumeration and legality cases, which the sealed `when`s force.
 */
sealed interface TargetSpec {
    /** The spell targets nothing (most spells); no `ChooseTargets` decision is surfaced. */
    data object None : TargetSpec

    /**
     * "Any target" (CR 115.4): one target that may be a creature, player, planeswalker, or
     * battle. Until creatures exist on the battlefield (Phase 3) the only legal choices the
     * engine can enumerate are players; the spec itself already covers the wider set, so
     * Phase 3 extends the *enumeration*, not this type.
     */
    data object AnyTarget : TargetSpec

    /**
     * "Target player" (CR 115.1a): one target that must be a player, never an object. Additive,
     * flagged core (the card-selection packet). Thought Scour's "Target player mills two cards" is the first
     * client. Narrower than [AnyTarget] on purpose — a creature is not a legal choice — so it is
     * its own member rather than a reuse of the any-target enumeration.
     */
    data object TargetPlayer : TargetSpec

    /**
     * An Aura's enchant ability (CR 303.4a, CR 601.2c): the one object it may be attached to,
     * restricted by [restriction]. Additive, flagged core (P4.1). An Aura spell targets the object
     * it will enchant while on the stack (CR 601.2c) and enters the battlefield attached to it
     * (CR 303.4f); the engine enumerates the legal choices from [restriction] (ADR-005) and
     * re-checks the target on resolution (CR 608.2b), fizzling if it is gone or illegal.
     *
     * @property restriction which objects the Aura may enchant (CR 303.4a).
     */
    data class Enchantable(
        val restriction: EnchantRestriction,
    ) : TargetSpec
}
