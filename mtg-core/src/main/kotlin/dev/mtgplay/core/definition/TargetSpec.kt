package dev.mtgplay.core.definition

/**
 * What a spell or ability demands as targets (CR 115): the declarative spec a [SpellDefinition],
 * a [TriggeredAbility], or an [ActivatedAbility] states and the engine enumerates legal choices
 * for (ADR-005 — the engine enumerates, the definition only describes).
 *
 * Sealed so the enumerator and the CR 608.2b resolution re-check handle every shape
 * exhaustively. P2.1 ships the two shapes the fixture pool needs; the model extends to further
 * members (target creature, target land, multi-target) without reshaping — a new member is a
 * new subtype plus its enumeration and legality cases, which the sealed `when`s force.
 *
 * The spec is *object-kind agnostic*: the same value describes what a spell targets at CR 601.2c,
 * what an activated ability targets at CR 602.2b, and what a triggered ability targets as it is
 * put on the stack at CR 603.3d (docs/design/targeted-abilities.md).
 */
sealed interface TargetSpec {
    /** The spell or ability targets nothing; no `ChooseTargets` decision is surfaced. */
    data object None : TargetSpec

    /**
     * "Target opponent" (CR 115.1a, CR 102.1): exactly one target, which must be a player other than
     * the one choosing. Additive, flagged core (`FW-ABILTGT`, docs/design/targeted-abilities.md) —
     * Lotleth Giant's enters-the-battlefield trigger. Narrower than [AnyTarget] twice over: no
     * permanent is offered (CR 115.4), and the chooser themself is not offered either, so the
     * enumeration is **decider-relative** — it depends on who is casting, activating, or placing the
     * trigger, not only on the board.
     *
     * A targeted player stops being a legal target only by leaving the game, which in a two-player
     * game *is* the game ending (CR 104.2a), so an object whose only target is a player can never
     * reach the CR 608.2b fizzle — the reachability note `Targets.kt` already records for the
     * players-only enumeration.
     */
    data object TargetOpponent : TargetSpec

    /**
     * "Any target" (CR 115.4): one target that may be a creature, player, planeswalker, or
     * battle. Until creatures exist on the battlefield (Phase 3) the only legal choices the
     * engine can enumerate are players; the spec itself already covers the wider set, so
     * Phase 3 extends the *enumeration*, not this type.
     */
    data object AnyTarget : TargetSpec

    /**
     * "Target creature" (CR 115.1a): one target that must be a creature on the battlefield, never a
     * player. Additive, flagged core (`P-TGT`, docs/gauntlet-card-triage.md) — Skred is the first
     * client. It is exactly the object half of [AnyTarget]: the same battlefield filter and the same
     * hexproof restriction (CR 702.11), with no player ever offered.
     *
     * Unlike the players-only specs this one's CR 608.2b fizzle is genuinely reachable — a targeted
     * creature that dies to a state-based action (CR 704.5g) before the spell resolves takes the
     * whole spell with it.
     */
    data object TargetCreature : TargetSpec

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
