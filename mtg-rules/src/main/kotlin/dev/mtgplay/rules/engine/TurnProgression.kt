package dev.mtgplay.rules.engine

import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep

/**
 * Every position of a turn in Comprehensive Rules order (CR 500.1): the beginning phase's
 * untap, upkeep, and draw steps; the precombat main phase; the combat phase's five steps; the
 * postcombat main phase; and the ending phase's end and cleanup steps.
 */
internal val CANONICAL_TURN_POSITIONS: List<TurnPosition> =
    listOf(
        TurnPosition(TurnPhase.BEGINNING, TurnStep.UNTAP),
        TurnPosition(TurnPhase.BEGINNING, TurnStep.UPKEEP),
        TurnPosition(TurnPhase.BEGINNING, TurnStep.DRAW),
        TurnPosition(TurnPhase.PRECOMBAT_MAIN, null),
        TurnPosition(TurnPhase.COMBAT, TurnStep.BEGINNING_OF_COMBAT),
        TurnPosition(TurnPhase.COMBAT, TurnStep.DECLARE_ATTACKERS),
        TurnPosition(TurnPhase.COMBAT, TurnStep.DECLARE_BLOCKERS),
        TurnPosition(TurnPhase.COMBAT, TurnStep.COMBAT_DAMAGE),
        TurnPosition(TurnPhase.COMBAT, TurnStep.END_OF_COMBAT),
        TurnPosition(TurnPhase.POSTCOMBAT_MAIN, null),
        TurnPosition(TurnPhase.ENDING, TurnStep.END),
        TurnPosition(TurnPhase.ENDING, TurnStep.CLEANUP),
    )

/** The position [turn] currently stands at. */
internal fun positionOf(turn: Turn): TurnPosition = TurnPosition(turn.phase, turn.step)

/**
 * The next position after [turn]'s current one in CR 500.1 order, skipping any skipped steps,
 * or `null` when the turn is over. The only skip in P1.2 is CR 103.8a: the starting player
 * skips the draw step of their first turn — and turn 1 is always the starting player's turn,
 * so the check is on the turn number (two-player games only, enforced at `MatchConfig`).
 */
internal fun positionAfter(turn: Turn): TurnPosition? {
    val index = CANONICAL_TURN_POSITIONS.indexOf(positionOf(turn))
    require(index >= 0) { "unknown turn position ${turn.phase}/${turn.step}" }
    var candidate = index + 1
    while (candidate < CANONICAL_TURN_POSITIONS.size && isSkipped(CANONICAL_TURN_POSITIONS[candidate], turn)) {
        candidate += 1
    }
    return CANONICAL_TURN_POSITIONS.getOrNull(candidate)
}

// Whether [position] is skipped for [turn]. Skipping a step means it does not occur at all — no
// turn-based action, no priority, no StepBegan event (CR 500.10).
// - CR 103.8a: in a two-player game, the player who plays first skips the draw step of their
//   first turn (turn 1 is always the starting player's).
// - CR 508.8: if no creatures were declared as attackers, the declare-blockers and combat-damage
//   steps are skipped. Combat is engaged (non-null) with an empty attacker list precisely in that
//   case, so the check is on the just-declared combat carried by the turn.
private fun isSkipped(
    position: TurnPosition,
    turn: Turn,
): Boolean =
    (position.step == TurnStep.DRAW && turn.number == 1) ||
        (position.step in COMBAT_STEPS_SKIPPED_WITHOUT_ATTACKERS && turn.combat?.attackers?.isEmpty() == true)

// CR 508.8: the two steps skipped when no attackers are declared.
private val COMBAT_STEPS_SKIPPED_WITHOUT_ATTACKERS: Set<TurnStep> =
    setOf(TurnStep.DECLARE_BLOCKERS, TurnStep.COMBAT_DAMAGE)

/**
 * Whether [position] is the first position of its phase — the moment the phase itself begins,
 * which is when the engine emits the phase-began event.
 */
internal fun isPhaseInitial(position: TurnPosition): Boolean =
    CANONICAL_TURN_POSITIONS.first { it.phase == position.phase } == position
