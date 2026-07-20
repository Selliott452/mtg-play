package dev.mtgplay.rules.engine

import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep

/**
 * One position within a turn: a phase, and the step within it when the phase has steps
 * (CR 500.1). Mirrors the shape [dev.mtgplay.core.state.Turn] enforces.
 */
internal data class TurnPosition(
    val phase: TurnPhase,
    val step: TurnStep?,
)
