package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.definition.HandRevealChoice
import dev.mtgplay.core.definition.RevealedCardOutcome
import dev.mtgplay.core.definition.RevealedCardRestriction
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingHandReveal
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.resolutionController
import dev.mtgplay.core.state.resolutionSourceCard
import dev.mtgplay.core.state.resolutionSourceId
import dev.mtgplay.core.state.resolutionTargets
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId

/*
 * The "target opponent reveals their hand and you choose a card from it" flow (`FW-HIDDENCHOICE`,
 * docs/design/exile-and-return.md §7) — Duress and Mesmeric Fiend.
 *
 * Three steps, of which only the middle one is a decision:
 *
 * 1. **The reveal (CR 701.16a) is not a decision.** A player told to reveal their hand reveals all of
 *    it; there is nothing to choose, so the engine performs it and emits [GameEvent.CardsRevealed]
 *    without pausing. From that moment the revealed cards are public and both seats see them.
 * 2. **The choice belongs to the resolving object's controller**, because both printed cards say "*you*
 *    choose". This is the single most important thing about this flow, and it is what makes it
 *    `FW-HIDDENCHOICE` rather than `FW-NONCTRLDEC`: the option list is public information at the moment
 *    it is offered, so there is no per-seat filtering question at all on the request side.
 * 3. **The outcome** is a discard by the revealer (Duress, CR 701.7a) or an exile recorded as linked
 *    information on the resolving source (Mesmeric Fiend, CR 701.3a + CR 607.2).
 *
 * The resolving object stays on top of the stack during the pause, so the request is a pure derivation
 * of the state (ADR-004).
 */

/**
 * Runs a "target opponent reveals their hand, you choose a card from it" [clause] (CR 701.16a): reveals
 * the targeted opponent's hand publicly, then pauses for the resolving object's controller to choose one
 * of the revealed cards satisfying the clause's restriction.
 *
 * With **no** matching card — a hand of nothing but lands against Mesmeric Fiend, or nothing but lands
 * and creatures against Duress — there is no legal choice, so no request is surfaced and the resolution
 * completes. The hand is still revealed: CR 701.16a's reveal happens whether or not a choice follows,
 * and an agent learning that its opponent holds only lands is real information the printed card grants.
 *
 * Fails loudly if the resolving object has no player target: the clause is declared only alongside
 * `TargetSpec.TargetOpponent`, so a missing one is an encoding defect rather than a rules case.
 */
internal fun orchestrateHandRevealChoice(
    state: GameState,
    entry: StackEntry,
    clause: HandRevealChoice,
): AdvanceResult {
    val decider = entry.resolutionController
    val revealer = revealerOf(entry)
    val hand = state.player(revealer).hand
    // CR 701.16a: the whole hand is revealed, not only the cards that could be chosen.
    val revealed = state.emit(GameEvent.CardsRevealed(revealer, hand.map { it.card }))
    val choosable = hand.filter { satisfiesRevealedRestriction(revealed, clause.restriction, it) }
    if (choosable.isEmpty()) return completeClauseResolution(revealed, entry)
    val paused =
        revealed.copy(
            pendingHandReveal =
                PendingHandReveal(
                    decider = decider,
                    revealer = revealer,
                    restriction = clause.restriction,
                    outcome = clause.outcome,
                    sourceId = entry.resolutionSourceId,
                    sourceCard = entry.resolutionSourceCard,
                ),
        )
    return AdvanceResult.NeedsDecision(paused, pendingHandRevealRequest(paused))
}

/**
 * The revealed-hand choice the open [GameState.pendingHandReveal] is waiting on (CR 701.16a): every card
 * in the revealer's hand satisfying the restriction, of which the controller picks one. Pure per ADR-004
 * — the hand is re-read rather than snapshotted, which is safe because no player receives priority
 * between the reveal and the choice.
 */
internal fun pendingHandRevealRequest(state: GameState): DecisionRequest.ChooseRevealedHandCard {
    val pending = state.pendingHandReveal ?: error("no hand-reveal choice is pending")
    val choosable =
        state
            .player(pending.revealer)
            .hand
            .filter { satisfiesRevealedRestriction(state, pending.restriction, it) }
    return DecisionRequest.ChooseRevealedHandCard(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        revealer = pending.revealer,
        sourceCard = pending.sourceCard,
        options = choosable.map { DecisionRequest.ChooseRevealedHandCard.Option(it.id, it.card) },
    )
}

/**
 * Applies the controller's choice from the revealed hand: the chosen card is **discarded** by its owner
 * (CR 701.7a — Duress) or **exiled** and recorded as linked information on the resolving source
 * (CR 701.3a, CR 607.2 — Mesmeric Fiend). Then the resolving object leaves the stack.
 *
 * Duress's discard routes through [discardApplyingReplacements] like every other discard in the engine,
 * so a madness card chosen this way is exiled instead and its reflexive cast fires — which is the
 * CR-correct interaction and comes for free from not special-casing the move.
 */
internal fun applyHandRevealChoice(
    state: GameState,
    objectId: ObjectId,
): AdvanceResult {
    val pending = state.pendingHandReveal ?: error("no hand-reveal choice is pending")
    val entry = resolvingClauseEntry(state)
    val cleared = state.copy(pendingHandReveal = null)
    val applied =
        when (pending.outcome) {
            RevealedCardOutcome.DISCARD -> discardApplyingReplacements(cleared, pending.revealer, objectId)
            RevealedCardOutcome.EXILE_LINKED -> exileChosenCardLinked(cleared, pending, objectId)
        }
    return completeClauseResolution(applied, entry)
}

/**
 * Exiles the chosen hand card (CR 701.3a) and records it on the resolving source as linked information
 * (CR 607.2), so Mesmeric Fiend's leaves-the-battlefield ability returns exactly this card.
 *
 * Fails loudly if the card is not in the revealer's hand: it was there when the request was built and no
 * player has had priority since, so a missing one is an engine defect.
 */
private fun exileChosenCardLinked(
    state: GameState,
    pending: PendingHandReveal,
    objectId: ObjectId,
): GameState {
    val hand = state.player(pending.revealer).hand
    val index = hand.indexOfFirst { it.id == objectId }
    require(index >= 0) { "CR 701.3a: the chosen card $objectId is not in ${pending.revealer}'s hand" }
    val chosen = hand[index]
    val (exileId, allocated) = state.allocateObjectId()
    val reborn = GameObject(id = exileId, card = chosen.card, owner = chosen.owner)
    val exiled =
        allocated
            .updatePlayer(pending.revealer) { it.copy(hand = it.hand.removingAt(index)) }
            .updateExile { it.adding(reborn) }
            .emit(GameEvent.CardExiledFromHand(pending.revealer, objectId, chosen.card, exileId))
    return recordLinkedExile(exiled, pending.sourceId, exileId)
}

/** The opponent a hand-reveal clause's resolving object targets (CR 115.1a) — its one player target. */
private fun revealerOf(entry: StackEntry): PlayerId =
    entry.resolutionTargets
        .filterIsInstance<Target.Player>()
        .singleOrNull()
        ?.id
        ?: error(
            "CR 701.16a: a hand-reveal clause reveals its target opponent's hand, but the resolving " +
                "${entry.resolutionSourceCard.name} targets no single player",
        )

/**
 * Whether a revealed hand card is a legal choice under [restriction] (CR 701.16a). Types are read
 * printed: a card in a hand is not affected by the CR 613 layer system (CR 109.3), so there is nothing
 * else it could mean.
 */
private fun satisfiesRevealedRestriction(
    state: GameState,
    restriction: RevealedCardRestriction,
    candidate: GameObject,
): Boolean {
    val types = state.definitions[candidate.card]?.characteristics?.cardTypes ?: return false
    return when (restriction) {
        RevealedCardRestriction.NONLAND -> CardType.LAND !in types
        RevealedCardRestriction.NONCREATURE_NONLAND -> CardType.LAND !in types && CardType.CREATURE !in types
    }
}
