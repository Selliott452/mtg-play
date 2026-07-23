package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.AttackerAssignment
import dev.mtgplay.core.state.CombatState
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId

/*
 * Trample combat-damage assignment (CR 702.19), the one player-facing choice of the combat-damage
 * step (CR 510). The controller of a blocked trampling attacker must assign at least lethal to each
 * surviving blocker (CR 510.1c), and may then assign the above-lethal excess to the defending player
 * (CR 702.19e). The decision is surfaced only when that excess is positive and a blocker survives —
 * otherwise the assignment is deterministic and the step deals it without a pause.
 */

/**
 * The trample-assignment decision due before this combat-damage step (CR 702.19e), or `null` when
 * none is: the first blocked trampling attacker (in declaration order) that still needs an assignment
 * this step ([needsTrampleAssignment]). Purely state-derived, so the pause recomputes to the same
 * request (ADR-004).
 */
internal fun pendingTrampleDecision(
    state: GameState,
    combat: CombatState,
): DecisionRequest.AssignTrampleDamage? {
    val step = currentDamageStep(state, combat)
    val assignment = step?.let { combat.attackers.firstOrNull { a -> needsTrampleAssignment(state, combat, a, it) } }
    return assignment?.let { trampleRequest(state, combat, it) }
}

// Whether [assignment]'s attacker needs a trample-assignment choice this [step] (CR 702.19e): it is
// blocked, has not already been assigned, deals this step, has trample, still has a surviving blocker,
// and its above-lethal excess is positive (a blocked trampler with no surviving blocker, or none to
// spare, has no choice — CR 702.19g / a deterministic assignment).
private fun needsTrampleAssignment(
    state: GameState,
    combat: CombatState,
    assignment: AttackerAssignment,
    step: DamageStep,
): Boolean {
    val attacker = assignment.attacker
    val survivors = orderedBlockersOf(combat, attacker)
    return attacker in combat.blockedAttackers &&
        attacker !in combat.trampleAssignments &&
        dealsThisStep(state, attacker, step) &&
        Keyword.TRAMPLE in effectiveKeywords(state, attacker) &&
        survivors.isNotEmpty() &&
        trampleExcess(state, attacker, survivors) > 0
}

// The request asking [assignment]'s controller how much of the excess to assign to the player: the
// options are the integers 0..excess, the answer's index being the chosen amount (CR 702.19e).
private fun trampleRequest(
    state: GameState,
    combat: CombatState,
    assignment: AttackerAssignment,
): DecisionRequest.AssignTrampleDamage {
    val attacker = assignment.attacker
    val excess = trampleExcess(state, attacker, orderedBlockersOf(combat, attacker))
    val active = state.turn.activePlayer
    return DecisionRequest.AssignTrampleDamage(
        id = DecisionRequestId(active, state.player(active).decisionsAnswered),
        attacker = attacker,
        attackerCard = state.battlefieldObject(attacker).card,
        defendingPlayer = assignment.defendingPlayer,
        options = (0..excess).toList(),
    )
}

/**
 * Records the chosen trample assignment (CR 702.19e): [amountToPlayer] of [request]'s attacker's
 * excess goes to the defending player, the rest overkills a blocker. Continues the combat-damage
 * step, which surfaces the next trampler's assignment or deals the step's damage once all are made.
 */
internal fun applyTrampleAssignment(
    state: GameState,
    request: DecisionRequest.AssignTrampleDamage,
    amountToPlayer: Int,
): AdvanceResult {
    val recorded =
        state.updateCombat {
            it.copy(trampleAssignments = it.trampleAssignments.putting(request.attacker, amountToPlayer))
        }
    return combatDamageStep(recorded)
}

/**
 * Assigns a blocked attacker's combat damage (CR 510.1c, CR 702.19e): at least lethal to each
 * surviving blocker in [orderedSurvivors] order, [toPlayer] to the defending player (trample; 0 for
 * a non-trampler), and the leftover onto the last blocker (overkill, outcome-irrelevant). Assigning
 * the player's share first guarantees each blocker still receives its full lethal, since [toPlayer]
 * never exceeds the above-lethal excess.
 */
internal fun assignBlockedDamage(
    state: GameState,
    attacker: ObjectId,
    orderedSurvivors: List<ObjectId>,
    defender: PlayerId,
    toPlayer: Int,
): List<DamageAssignment> {
    var remaining = effectivePower(state, attacker)
    val assignments = mutableListOf<DamageAssignment>()
    if (toPlayer > 0) {
        assignments.add(DamageAssignment(attacker, Target.Player(defender), toPlayer))
        remaining -= toPlayer
    }
    orderedSurvivors.forEachIndexed { index, blocker ->
        // The last blocker absorbs the whole remainder: its lethal plus any overkill.
        val amount = if (index == orderedSurvivors.lastIndex) remaining else minOf(remaining, lethalTo(state, blocker))
        remaining -= amount
        assignments.add(DamageAssignment(attacker, Target.Permanent(blocker), amount))
    }
    return assignments
}

// The above-lethal excess of [attacker] over its surviving blockers (CR 702.19e): its power minus
// the sum of each survivor's remaining lethal, floored at zero when the blockers can soak it all.
private fun trampleExcess(
    state: GameState,
    attacker: ObjectId,
    survivors: List<ObjectId>,
): Int = effectivePower(state, attacker) - survivors.sumOf { lethalTo(state, it) }

// CR 510.1c: lethal to a blocker is its toughness minus damage already marked, never negative.
private fun lethalTo(
    state: GameState,
    blocker: ObjectId,
): Int = (effectiveToughness(state, blocker) - state.battlefieldObject(blocker).damageMarked).coerceAtLeast(0)
