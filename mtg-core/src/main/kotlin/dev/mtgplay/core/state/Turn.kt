package dev.mtgplay.core.state

import dev.mtgplay.core.identity.PlayerId

/**
 * Where the game stands within whose turn (CR 500): the active player (CR 102.1), the turn
 * number, and the current phase and step.
 *
 * Construction enforces the CR 500 shape: a main phase carries no step (CR 505); every other
 * phase is always in exactly one of its own steps.
 *
 * @property activePlayer the player whose turn it is (CR 102.1).
 * @property number the turn number, starting at 1.
 * @property phase the current phase (CR 500.1).
 * @property step the current step — present exactly when [phase] has steps.
 */
data class Turn(
    val activePlayer: PlayerId,
    val number: Int,
    val phase: TurnPhase,
    val step: TurnStep?,
) {
    init {
        require(number >= 1) { "turn numbers start at 1, was $number" }
        val current = step
        if (phase.hasSteps) {
            requireNotNull(current) { "CR 500.1: $phase divides into steps; a current step is required" }
            require(current.phase == phase) { "step $current belongs to ${current.phase}, not $phase" }
        } else {
            require(current == null) { "CR 505: the main phases have no steps; got $current in $phase" }
        }
    }
}
