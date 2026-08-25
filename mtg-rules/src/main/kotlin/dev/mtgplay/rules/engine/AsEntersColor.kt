package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.AsEntersColorChoice
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingColorChoice
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId

/*
 * The "as this permanent enters, choose a colour" as-enters flow (CR 614.12) — Utopia Sprawl and the Gate
 * cycle. A permanent that chooses a colour pauses **before** entering; the controller chooses, and the
 * colour is stored on the entering object where the card's mana abilities read it. Split from
 * StackResolution.kt so each file stays within its function budget.
 *
 * Two routes reach the battlefield and both pause here: a resolving permanent spell (CR 608.3) and the
 * play-land special action (CR 305.1, PlayLand.kt). The choice is the same one either way — CR 614.12
 * knows nothing about how the permanent got there — so only the resume differs, and which route to resume
 * is recorded on the pending record rather than inferred from the stack.
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
 * (CR 614.12): the entering permanent's controller chooses one of the colours its printed line admits. A
 * pure function of the state (ADR-004) — the entering object is either the resolving spell on top of the
 * stack or, for a land, the card still in the decider's hand
 * ([dev.mtgplay.core.state.PendingColorChoice.playedLand]).
 *
 * **The option list is the printed one, never all five by default.** "As this land enters, choose a color
 * other than white" removes white from the enumeration rather than making it a choice the player is
 * expected not to take: an option the rules forbid is the ADR-005 defect in its most expensive direction.
 */
internal fun pendingColorChoiceRequest(state: GameState): DecisionRequest.ChooseColor {
    val pending = state.pendingColorChoice ?: error("no colour choice is pending")
    val entering = enteringColorChooser(state, pending)
    return DecisionRequest.ChooseColor(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        cardObjectId = entering.id,
        card = entering.card,
        options = asEntersColorOptions(colorChoiceOf(state, entering.card)),
    )
}

/**
 * Applies the chosen colour of an as-enters choice (CR 614.12): clears the pending choice and completes
 * the entry with [color] stored on the entering object — a resolving permanent spell's
 * ([enterResolvedPermanent]) or a played land's ([completePlayLand]).
 */
internal fun applyChosenColor(
    state: GameState,
    color: Color,
): AdvanceResult {
    val pending = state.pendingColorChoice ?: error("no colour choice is pending")
    val cleared = state.copy(pendingColorChoice = null)
    val playedLand = pending.playedLand
    if (playedLand != null) {
        // CR 614.12 resumption: re-derive the zone the card is still sitting in rather than carrying
        // a source across the pause. The card cannot have moved while the choice was open, and a
        // recorded source is one more thing that could disagree with the board (ADR-004 — a
        // resumption is a pure function of the state it is handed).
        val source =
            if (cleared.player(pending.decider).hand.any { it.id == playedLand }) {
                CastSource.HAND
            } else {
                CastSource.EXILE
            }
        return completePlayLand(cleared, pending.decider, playedLand, color, source)
    }
    val entry =
        state.sharedZones.stack.lastOrNull() as? StackEntry.Spell
            ?: error("CR 614.12: an as-enters colour choice requires a resolving spell on top of the stack")
    return enterResolvedPermanent(cleared, entry, color)
}

/**
 * The colours a [AsEntersColorChoice] admits (CR 614.12), in the [Color] declaration order the engine
 * enumerates everything in (ADR-005, ADR-006): all five, less the one the printed line forbids.
 */
internal fun asEntersColorOptions(choice: AsEntersColorChoice): List<Color> =
    Color.entries.filterNot { it == choice.excluding }

/**
 * The object whose entry an open colour choice interrupted: the card in the decider's hand for a played
 * land, otherwise the permanent spell resolving on top of the stack. Fails loudly rather than guessing —
 * a pause whose object has gone is an engine defect.
 */
private fun enteringColorChooser(
    state: GameState,
    pending: PendingColorChoice,
): GameObject {
    val playedLand = pending.playedLand
    if (playedLand == null) {
        val entry =
            state.sharedZones.stack.lastOrNull() as? StackEntry.Spell
                ?: error("CR 614.12: an as-enters colour choice requires a resolving spell on top of the stack")
        return entry.obj
    }
    return state.player(pending.decider).hand.firstOrNull { it.id == playedLand }
        ?: error(
            "CR 305.1: the land $playedLand whose colour choice is pending is no longer in " +
                "${pending.decider}'s hand",
        )
}

/** The [AsEntersColorChoice] the registered definition of [card] declares; fails loudly if it declares none. */
private fun colorChoiceOf(
    state: GameState,
    card: CardRef,
): AsEntersColorChoice =
    state.definitions[card]?.asEntersColorChoice
        ?: error("CR 614.12: ${card.name} has no as-enters colour choice, so none can be pending")
