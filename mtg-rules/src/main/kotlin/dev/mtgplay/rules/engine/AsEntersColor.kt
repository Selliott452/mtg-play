package dev.mtgplay.rules.engine

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId

/*
 * The "as this permanent enters, choose a colour" mid-resolution flow (CR 614.12) — Utopia Sprawl. A
 * permanent that chooses a colour pauses its resolution before entering; the controller chooses, and the
 * colour is stored on the entering object where the card's triggered mana ability reads it. Split from
 * StackResolution.kt so each file stays within its function budget.
 */

/**
 * Completes a permanent spell's entry (CR 608.3): puts the resolving [entry] onto the battlefield with
 * [chosenColor] stored on the entering object (Utopia Sprawl, or null), announces the entry and any Aura
 * attachment, fires its enters-the-battlefield triggers (CR 603.6a), then grants a fresh priority round.
 * Shared by the ordinary permanent-resolution path and the resume after an as-enters colour choice
 * ([applyChosenColor]).
 */
internal fun enterResolvedPermanent(
    state: GameState,
    entry: StackEntry.Spell,
    chosenColor: Color?,
): AdvanceResult {
    val (entered, battlefieldId) = putResolvedSpellOntoBattlefield(state, entry, chosenColor)
    // CR 603.6a: narrating the entry and firing the permanent's own enters-the-battlefield triggers
    // are one indivisible step ([announceBattlefieldEntry]); the fired triggers are put on the stack
    // at the priority grant that follows (CR 603.3b). Detection emits nothing of its own, so the
    // announcement stays the first word about this entry in the log.
    val announced =
        announceBattlefieldEntry(
            entered,
            battlefieldId,
            GameEvent.PermanentEntered(entry.controller, entry.obj.id, entry.obj.card, battlefieldId),
        )
    // CR 303.4f: an Aura enters attached; announce the attachment after it has entered.
    val attachedTo = entered.battlefieldObject(battlefieldId).attachedTo
    val withAura =
        if (attachedTo == null) {
            announced
        } else {
            announced.emit(GameEvent.AuraAttached(battlefieldId, attachedTo, entry.obj.card))
        }
    return grantPriorityRound(withAura)
}

/**
 * The as-enters colour-choice request the open [dev.mtgplay.core.state.PendingColorChoice] is waiting on
 * (CR 614.12): the resolving permanent's controller chooses one of the five colours. A pure function of
 * the state (ADR-004) — the resolving spell is the top of the stack.
 */
internal fun pendingColorChoiceRequest(state: GameState): DecisionRequest.ChooseColor {
    val pending = state.pendingColorChoice ?: error("no colour choice is pending")
    val entry =
        state.sharedZones.stack.lastOrNull() as? StackEntry.Spell
            ?: error("CR 614.12: an as-enters colour choice requires a resolving spell on top of the stack")
    return DecisionRequest.ChooseColor(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        cardObjectId = entry.obj.id,
        card = entry.obj.card,
        options = Color.entries.toList(),
    )
}

/**
 * Applies the chosen colour of an as-enters choice (CR 614.12): clears the pending choice and completes
 * the resolving permanent's entry with [color] stored on it ([enterResolvedPermanent]).
 */
internal fun applyChosenColor(
    state: GameState,
    color: Color,
): AdvanceResult {
    state.pendingColorChoice ?: error("no colour choice is pending")
    val entry =
        state.sharedZones.stack.lastOrNull() as? StackEntry.Spell
            ?: error("CR 614.12: an as-enters colour choice requires a resolving spell on top of the stack")
    return enterResolvedPermanent(state.copy(pendingColorChoice = null), entry, color)
}
