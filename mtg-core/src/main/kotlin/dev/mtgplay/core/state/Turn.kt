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
 * @property landsPlayedThisTurn how many lands have been played this turn (CR 305.2 — normally
 *   at most one; the play-land legality gate lives in `mtg-rules`, P2.2). Carried on the turn
 *   rather than per player (architect-flagged placement) because only the active player may
 *   play a land at all (CR 305.1), so one per-turn counter suffices and resets itself when the
 *   next turn's [Turn] is constructed. An `Int`, not a flag, so effects granting additional
 *   land plays (outside the MVP pool) extend the *limit*, not this shape.
 * @property spellsCastThisTurn how many spells have finished being cast this turn (CR 601.2i), by
 *   **every** player. Additive, flagged core (`W9-C`) — the count storm reads (CR 702.40a: "copy it for
 *   each spell cast before it this turn").
 *
 *   Carried on the turn rather than per player, and that placement *is* the rule rather than a
 *   convenience: storm counts every seat's spells, not only its controller's, so a per-player tally
 *   would have to be summed at every read and could disagree with itself. It is [landsPlayedThisTurn]'s
 *   sibling in every structural respect — a per-turn game-wide counter that resets itself when the next
 *   turn's [Turn] is constructed, so nothing has to remember to clear it — and differs from
 *   [PlayerState.drawsThisTurn], which is per player because "your third card in a turn" is a per-seat
 *   question and is explicitly reset each turn.
 *
 *   **It counts casts, not stack residents.** A spell that was countered, that fizzled, or that has long
 *   since resolved still counts (CR 702.40a says "cast", and CR 608 does not un-cast anything), and a
 *   **copy** put onto the stack does *not* — a copy is created on the stack rather than cast
 *   (CR 707.10a), which is what stops storm copies from feeding a later storm spell. Incremented at
 *   CR 601.2i, the moment a cast becomes complete, so a spell never counts itself.
 * @property combat the combat progress of the current combat phase (CR 506–511), or `null`
 *   outside it (additive, flagged core, P3.1). Carried on the turn — like [landsPlayedThisTurn],
 *   combat is a per-turn phenomenon that resets itself when the next turn's [Turn] is
 *   constructed; see [CombatState]. Present only during the combat phase (CR 506.1), and only
 *   once the declare-attackers turn-based action has engaged combat (CR 508.1).
 */
data class Turn(
    val activePlayer: PlayerId,
    val number: Int,
    val phase: TurnPhase,
    val step: TurnStep?,
    val landsPlayedThisTurn: Int = 0,
    val combat: CombatState? = null,
    val spellsCastThisTurn: Int = 0,
) {
    init {
        require(number >= 1) { "turn numbers start at 1, was $number" }
        require(spellsCastThisTurn >= 0) {
            "CR 601.2i: spells cast this turn must be non-negative, was $spellsCastThisTurn"
        }
        require(landsPlayedThisTurn >= 0) {
            "CR 305.2: lands played this turn must be non-negative, was $landsPlayedThisTurn"
        }
        require(combat == null || phase == TurnPhase.COMBAT) {
            "CR 506.1: combat state exists only during the combat phase, not $phase"
        }
        val current = step
        if (phase.hasSteps) {
            requireNotNull(current) { "CR 500.1: $phase divides into steps; a current step is required" }
            require(current.phase == phase) { "step $current belongs to ${current.phase}, not $phase" }
        } else {
            require(current == null) { "CR 505: the main phases have no steps; got $current in $phase" }
        }
    }
}
