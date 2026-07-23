package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.AttackerAssignment
import dev.mtgplay.core.state.BlockAssignment
import dev.mtgplay.core.state.CombatState
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet

/*
 * Applying the three combat decisions (CR 508–509). Each records its choice into the turn's
 * [CombatState] and hands back to [resumeCombat], which decides whether another combat decision is
 * due or the step's priority round is granted — so the transition and the pause derivation stay in
 * lockstep (ADR-004).
 */

/**
 * Applies the declare-attackers choice (CR 508.1): records the chosen attackers as a fresh
 * [CombatState], taps each attacker that lacks vigilance (CR 508.1f), and continues. An empty
 * choice engages an empty combat, which CR 508.8 later skips forward from.
 */
internal fun applyDeclareAttackers(
    state: GameState,
    request: DecisionRequest.DeclareAttackers,
    decision: Decision,
): AdvanceResult {
    check(decision is Decision.MultiSelect) { "unreachable: decision shape was validated against the request" }
    val attackers =
        decision.indices
            .map { request.options[it] }
            .map { AttackerAssignment(it.attacker, it.defendingPlayer) }
            .toPersistentList()
    val engaged = state.copy(turn = state.turn.copy(combat = CombatState(attackers = attackers)))
    val tapped = tapNonVigilanceAttackers(engaged, attackers)
    return resumeCombat(tapped.emit(GameEvent.AttackersDeclared(attackers.toList())))
}

// CR 508.1f: declaring a creature as an attacker taps it, unless it has vigilance (CR 702.21b).
private fun tapNonVigilanceAttackers(
    state: GameState,
    attackers: List<AttackerAssignment>,
): GameState =
    attackers.fold(state) { current, assignment ->
        if (Keyword.VIGILANCE in effectiveKeywords(current, assignment.attacker)) {
            current
        } else {
            tapPermanent(current, assignment.attacker)
        }
    }

// Taps the untapped battlefield object [id], emitting ObjectTapped (CR 701.21a).
private fun tapPermanent(
    state: GameState,
    id: ObjectId,
): GameState {
    val battlefield = state.sharedZones.battlefield
    val index = battlefield.indexOfFirst { it.id == id }
    require(index >= 0) { "attacker $id is not on the battlefield" }
    val obj = battlefield[index]
    val tapped = battlefield.removingAt(index).addingAt(index, obj.copy(tapped = true))
    return state
        .copy(sharedZones = state.sharedZones.copy(battlefield = tapped))
        .emit(GameEvent.ObjectTapped(obj.id, obj.card))
}

/**
 * Applies the declare-blockers choice (CR 509.1): records the chosen blocks — and the set of
 * attackers that thereby became blocked (CR 509.1h; each stays blocked for the rest of combat even
 * if its blockers later leave) — then continues, which next surfaces a [DecisionRequest.OrderBlockers]
 * for any attacker blocked by two or more creatures (CR 509.2), or grants priority when none need
 * ordering.
 */
internal fun applyDeclareBlockers(
    state: GameState,
    request: DecisionRequest.DeclareBlockers,
    decision: Decision,
): AdvanceResult {
    check(decision is Decision.MultiSelect) { "unreachable: decision shape was validated against the request" }
    val blocks =
        decision.indices
            .map { request.options[it] }
            .map { BlockAssignment(it.blocker, it.attacker) }
            .toPersistentList()
    val blockedAttackers = blocks.map(BlockAssignment::attacker).toPersistentSet()
    val blocked = state.updateCombat { it.copy(blocks = blocks, blockedAttackers = blockedAttackers) }
    return resumeCombat(blocked.emit(GameEvent.BlockersDeclared(blocks.toList())))
}

/**
 * Applies one attacker's blocker-order choice (CR 509.2): records the permutation as that
 * attacker's damage-assignment order and continues — the next unordered multi-blocked attacker,
 * then priority.
 */
internal fun applyOrderBlockers(
    state: GameState,
    request: DecisionRequest.OrderBlockers,
    decision: Decision,
): AdvanceResult {
    check(decision is Decision.MultiSelect) { "unreachable: decision shape was validated against the request" }
    val order = decision.indices.map { request.options[it].blocker }.toPersistentList()
    val ordered = state.updateCombat { it.copy(blockerOrder = it.blockerOrder.putting(request.attacker, order)) }
    return resumeCombat(ordered.emit(GameEvent.BlockerOrderChosen(request.attacker, order.toList())))
}
