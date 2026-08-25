package dev.mtgplay.rules.engine

import dev.mtgplay.core.state.GameState
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
 * The next position after [state]'s current one in CR 500.1 order, skipping any skipped step or
 * phase, or `null` when the turn is over. See [isSkipped] for the three skips the engine knows.
 *
 * **The loop walks over consecutive skipped positions**, which is what lets a *phase* be skipped by a
 * predicate that speaks only of positions: a scheduled combat skip answers `true` for all five combat
 * positions at once, so precombat main advances straight to postcombat main in a single call and no
 * `PhaseBegan(COMBAT)` is ever emitted (CR 500.10).
 *
 * It takes the whole [state] rather than the [Turn] because the third skip is a fact about a *player*,
 * not about the turn's shape. That widening is `W8-G`'s, and it is the whole of the "skip framework"
 * `TapEffects.kt` recorded Stonehorn Dignitary as needing.
 */
internal fun positionAfter(state: GameState): TurnPosition? {
    val turn = state.turn
    val index = CANONICAL_TURN_POSITIONS.indexOf(positionOf(turn))
    require(index >= 0) { "unknown turn position ${turn.phase}/${turn.step}" }
    var candidate = index + 1
    while (candidate < CANONICAL_TURN_POSITIONS.size && isSkipped(CANONICAL_TURN_POSITIONS[candidate], state)) {
        candidate += 1
    }
    return CANONICAL_TURN_POSITIONS.getOrNull(candidate)
}

// Whether [position] is skipped for [state]'s turn. Skipping a step or phase means it does not occur at
// all — no turn-based action, no priority, no StepBegan or PhaseBegan event (CR 500.10).
// - CR 103.8a: in a two-player game, the player who plays first skips the draw step of their
//   first turn (turn 1 is always the starting player's).
// - CR 508.8: if no creatures were declared as attackers, the declare-blockers and combat-damage
//   steps are skipped. Combat is engaged (non-null) with an empty attacker list precisely in that
//   case, so the check is on the just-declared combat carried by the turn.
// - CR 500.10: the active player has a scheduled combat-phase skip standing against them (Stonehorn
//   Dignitary). The whole phase goes, not a step of it, so every combat position answers true — and it
//   is the *active* player's counter that is read, because a combat phase belongs to whoever's turn it
//   is. This predicate only reports the skip; `spendScheduledCombatSkip` is what consumes it.
private fun isSkipped(
    position: TurnPosition,
    state: GameState,
): Boolean {
    val turn = state.turn
    return (position.step == TurnStep.DRAW && turn.number == 1) ||
        (position.step in COMBAT_STEPS_SKIPPED_WITHOUT_ATTACKERS && turn.combat?.attackers?.isEmpty() == true) ||
        (position.phase == TurnPhase.COMBAT && state.player(turn.activePlayer).combatPhasesToSkip > 0)
}

// CR 508.8: the two steps skipped when no attackers are declared.
private val COMBAT_STEPS_SKIPPED_WITHOUT_ATTACKERS: Set<TurnStep> =
    setOf(TurnStep.DECLARE_BLOCKERS, TurnStep.COMBAT_DAMAGE)

/**
 * Spends one of the active player's scheduled combat-phase skips when the walk from [state]'s current
 * position to [next] has just stepped over the combat phase (CR 500.10), and returns [state] unchanged
 * otherwise.
 *
 * **Consumption is separated from detection**, and it has to be: [positionAfter] is a pure query that
 * several checks could in principle repeat, while a scheduled skip must be spent exactly once. Putting
 * the decrement here — at the single transition that actually moves the turn on — is what makes "their
 * *next* combat phase" mean one phase rather than every phase forever.
 *
 * The precombat main phase is the only position from which the walk can reach combat, and combat always
 * follows it unless something skipped it, so "we were in precombat main and the next position is not a
 * combat one" is exactly the skip having happened. The CR 508.8 attacker skip cannot be confused with it:
 * that one drops two *steps* from inside a combat phase that did begin, and it is never consulted from
 * precombat main because `turn.combat` is still null there.
 */
internal fun spendScheduledCombatSkip(
    state: GameState,
    next: TurnPosition?,
): GameState {
    val turn = state.turn
    val steppedOverCombat = turn.phase == TurnPhase.PRECOMBAT_MAIN && next?.phase != TurnPhase.COMBAT
    if (!steppedOverCombat) return state
    val scheduled = state.player(turn.activePlayer).combatPhasesToSkip
    check(scheduled > 0) {
        "CR 500.10: the combat phase was stepped over with no skip scheduled against " +
            "${turn.activePlayer}; the only reason [positionAfter] passes combat is a scheduled skip"
    }
    return state.updatePlayer(turn.activePlayer) { it.copy(combatPhasesToSkip = scheduled - 1) }
}

/**
 * Whether [position] is the first position of its phase — the moment the phase itself begins,
 * which is when the engine emits the phase-began event.
 */
internal fun isPhaseInitial(position: TurnPosition): Boolean =
    CANONICAL_TURN_POSITIONS.first { it.phase == position.phase } == position
