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
}
