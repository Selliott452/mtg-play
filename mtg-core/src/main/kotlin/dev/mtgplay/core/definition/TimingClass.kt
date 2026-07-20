package dev.mtgplay.core.definition

/**
 * When a spell may be cast (CR 117.1a): the two timing classes the MVP pool exercises.
 *
 * This is declared by the [SpellDefinition] rather than derived from the card types so that
 * later phases can grant instant-speed to sorcery-typed cards (flash-like permissions, madness'
 * cast-anytime window) without touching the type line. The casting pipeline (P2.1, `mtg-rules`)
 * is the sole consumer; enumeration excludes a cast whose timing class forbids the current
 * window (ADR-005 — illegality is unrepresentable, not rejected).
 */
enum class TimingClass {
    /**
     * Castable whenever its controller has priority (CR 117.1a) — the timing of instants
     * (CR 304.1).
     */
    INSTANT_SPEED,

    /**
     * Castable only by the active player, during a main phase of their own turn, when the stack
     * is empty (CR 117.1a) — the timing of sorceries (CR 307.1).
     */
    SORCERY_SPEED,
}
