package dev.mtgplay.core.state

/**
 * The steps of a turn (CR 500.1), each belonging to one [TurnPhase]. Nouns only: when steps
 * happen, repeat (the additional combat damage step that first strike creates, CR 510), or are
 * skipped is rules-engine territory (P1.2 and Phase 3).
 *
 * @property phase the phase this step belongs to.
 */
enum class TurnStep(
    val phase: TurnPhase,
) {
    /** The untap step (CR 502). */
    UNTAP(TurnPhase.BEGINNING),

    /** The upkeep step (CR 503). */
    UPKEEP(TurnPhase.BEGINNING),

    /** The draw step (CR 504). */
    DRAW(TurnPhase.BEGINNING),

    /** The beginning of combat step (CR 507). */
    BEGINNING_OF_COMBAT(TurnPhase.COMBAT),

    /** The declare attackers step (CR 508). */
    DECLARE_ATTACKERS(TurnPhase.COMBAT),

    /** The declare blockers step (CR 509). */
    DECLARE_BLOCKERS(TurnPhase.COMBAT),

    /** The combat damage step (CR 510); first strike adds a second one (rules territory). */
    COMBAT_DAMAGE(TurnPhase.COMBAT),

    /** The end of combat step (CR 511). */
    END_OF_COMBAT(TurnPhase.COMBAT),

    /** The end step (CR 513). */
    END(TurnPhase.ENDING),

    /** The cleanup step (CR 514). */
    CLEANUP(TurnPhase.ENDING),
}
