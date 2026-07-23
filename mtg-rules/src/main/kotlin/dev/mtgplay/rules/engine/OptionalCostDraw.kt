package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.definition.OptionalCostMode
import dev.mtgplay.core.definition.OptionalCostThenDraw
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingOptionalCostDraw
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.effect.drawCards

/*
 * The optional "you may [discard a card | sacrifice a land]; if you do, draw N" spell-resolution flow
 * (CR 601.3b) — Highway Robbery. The spell-resolution generalizer of the trigger-scoped optional discard-
 * then-draw (OptionalDiscardDraw.kt), adding the sacrifice-a-land alternative mode. The resolving spell
 * stays on top of the stack (like the library-reveal flow) so its declaration — the draw count and the
 * offered modes — is a pure derivation of the state (ADR-004). The engine offers the controller a mode
 * choice (decline, or one performable mode), then that mode's object selection; on the selection it pays the
 * cost (a discard through the CR 614/616 framework so a madness card is exiled instead, or a land sacrifice)
 * and draws, then the spell leaves the stack.
 */

/**
 * Runs a spell's optional cost-then-draw [clause] (CR 601.3b) after its ordinary resolution: if any mode is
 * performable the engine pauses for the mode choice; otherwise the "may" cannot be taken, so no cost is paid,
 * no card is drawn, and the spell simply leaves the stack. The resolving spell [entry] stays on top of the
 * stack during the pause.
 */
internal fun orchestrateOptionalCostDraw(
    state: GameState,
    entry: StackEntry.Spell,
    clause: OptionalCostThenDraw,
): AdvanceResult {
    val decider = entry.controller
    val modes = performableModes(state, decider, clause)
    // CR 601.3b: no mode is performable, so the "may" cannot be taken — no draw; the spell leaves the stack.
    if (modes.isEmpty()) return completeInstantSorceryResolution(state, entry)
    val paused = state.copy(pendingOptionalCostDraw = PendingOptionalCostDraw(decider))
    return AdvanceResult.NeedsDecision(paused, pendingCostModeRequest(paused))
}

/**
 * The mode-choice request the open [GameState.pendingOptionalCostDraw] is waiting on (CR 601.3b): the
 * performable cost modes plus a decline. A pure function of the state (ADR-004) — the resolving spell (with
 * its clause) is the top of the stack.
 */
internal fun pendingCostModeRequest(state: GameState): DecisionRequest.ChooseCostMode {
    val pending = state.pendingOptionalCostDraw ?: error("no optional cost-then-draw is pending")
    val clause = resolvingCostDrawClause(state)
    return DecisionRequest.ChooseCostMode(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        prompt = "pay a cost to draw ${clause.drawCount}",
        options = performableModes(state, pending.decider, clause),
    )
}

/**
 * The cost-object selection request the accepted mode is waiting on (CR 601.3b, CR 701.8/701.17): the hand
 * cards to discard, or the controlled lands to sacrifice, per the chosen mode. Pure per ADR-004.
 */
internal fun pendingOptionalCostObjectRequest(state: GameState): DecisionRequest.ChooseOptionalCostObject {
    val pending = state.pendingOptionalCostDraw ?: error("no optional cost-then-draw is pending")
    val mode = pending.chosenMode ?: error("a cost-object selection requires a chosen mode")
    val objects =
        when (mode) {
            OptionalCostMode.DiscardCard -> state.player(pending.decider).hand
            OptionalCostMode.SacrificeLand -> controlledLands(state, pending.decider)
        }
    return DecisionRequest.ChooseOptionalCostObject(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        options = objects.map { DecisionRequest.ChooseOptionalCostObject.Option(it.id, it.card) },
    )
}

/**
 * Applies the mode choice (CR 601.3b): a `null` [mode] declines — no cost, no draw, and the spell leaves the
 * stack; any other mode records it on the pending clause and pauses for that mode's cost-object selection.
 */
internal fun applyCostModeChoice(
    state: GameState,
    mode: OptionalCostMode?,
): AdvanceResult {
    val pending = state.pendingOptionalCostDraw ?: error("no optional cost-then-draw is pending")
    if (mode == null) {
        return completeInstantSorceryResolution(state.copy(pendingOptionalCostDraw = null), resolvingSpellEntry(state))
    }
    val chosen = state.copy(pendingOptionalCostDraw = pending.copy(chosenMode = mode))
    return AdvanceResult.NeedsDecision(chosen, pendingOptionalCostObjectRequest(chosen))
}

/**
 * Applies the cost-object selection (CR 601.3b): pays the chosen mode's cost with [objectId] — a discard
 * through the CR 614/616 framework (so a madness card is exiled instead), or a land sacrifice — draws the
 * clause's cards, then the spell leaves the stack.
 */
internal fun applyOptionalCostObject(
    state: GameState,
    objectId: ObjectId,
): AdvanceResult {
    val pending = state.pendingOptionalCostDraw ?: error("no optional cost-then-draw is pending")
    val mode = pending.chosenMode ?: error("a cost-object selection requires a chosen mode")
    val clause = resolvingCostDrawClause(state)
    val entry = resolvingSpellEntry(state)
    val cleared = state.copy(pendingOptionalCostDraw = null)
    val paid =
        when (mode) {
            OptionalCostMode.DiscardCard -> discardApplyingReplacements(cleared, pending.decider, objectId)
            OptionalCostMode.SacrificeLand -> sacrificePermanents(cleared, pending.decider, listOf(objectId))
        }
    // CR 601.3b: "if you do, draw" — the draw follows the paid cost, then the spell leaves the stack.
    return completeInstantSorceryResolution(drawCards(paid, pending.decider, clause.drawCount), entry)
}

/** The offered modes of [clause] that [decider] can actually perform right now (CR 601.3b), in printed order. */
private fun performableModes(
    state: GameState,
    decider: PlayerId,
    clause: OptionalCostThenDraw,
): List<OptionalCostMode> =
    clause.modes.filter { mode ->
        when (mode) {
            OptionalCostMode.DiscardCard -> state.player(decider).hand.isNotEmpty()
            OptionalCostMode.SacrificeLand -> controlledLands(state, decider).isNotEmpty()
        }
    }

/** The lands [decider] controls (CR 701.17) — battlefield permanents they own whose printed types include land. */
private fun controlledLands(
    state: GameState,
    decider: PlayerId,
): List<GameObject> =
    state.sharedZones.battlefield.filter { obj ->
        obj.owner == decider &&
            state.definitions[obj.card]
                ?.characteristics
                ?.cardTypes
                ?.contains(CardType.LAND) == true
    }

/** The optional cost-then-draw clause of the resolving spell on top of the stack (CR 601.3b); fails loudly. */
private fun resolvingCostDrawClause(state: GameState): OptionalCostThenDraw =
    (state.sharedZones.stack.lastOrNull() as? StackEntry.Spell)?.definition?.optionalCostThenDraw
        ?: error("CR 601.3b: an optional cost-then-draw requires a resolving spell with the clause on the stack")

/** The resolving spell entry on top of the stack; fails loudly if it is not a spell (an engine defect). */
private fun resolvingSpellEntry(state: GameState): StackEntry.Spell =
    state.sharedZones.stack.lastOrNull() as? StackEntry.Spell
        ?: error("CR 608.1: an optional cost-then-draw requires a resolving spell on top of the stack")
