package dev.mtgplay.core.state

/**
 * The five phases of a turn (CR 500.1): beginning, precombat main, combat, postcombat main,
 * and ending. Nouns only — progression logic is the engine's (packet P1.2).
 *
 * @property hasSteps whether the phase divides into steps; the two main phases have none
 *   (CR 505).
 */
enum class TurnPhase(
    val hasSteps: Boolean,
) {
    /** The beginning phase (CR 501): untap, upkeep, and draw steps. */
    BEGINNING(hasSteps = true),

    /** The precombat main phase (CR 505); main phases have no steps. */
    PRECOMBAT_MAIN(hasSteps = false),

    /** The combat phase (CR 506). */
    COMBAT(hasSteps = true),

    /** The postcombat main phase (CR 505); main phases have no steps. */
    POSTCOMBAT_MAIN(hasSteps = false),

    /** The ending phase (CR 512): end and cleanup steps. */
    ENDING(hasSteps = true),
}
