package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.ChosenTypeReveal
import dev.mtgplay.core.definition.RevealedCardFilter
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingTypeChoice
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.resolutionController
import dev.mtgplay.core.state.resolutionSourceCard
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId

/*
 * The "choose a card type, then reveal the top N and partition them" clause (CR 609.4, CR 701.16) —
 * Winding Way's "Choose creature or land. Reveal the top four cards of your library. Put all cards of
 * the chosen type revealed this way into your hand and the rest into your graveyard." Additive
 * (`W8-D`), a member of the `FW-CLAUSEHOOK` family: orchestrate → request → apply.
 *
 * **Two things distinguish it from the CR 701.16 reveal it superficially resembles, and both are rules,
 * not preferences.**
 *
 * 1. **The choice is resolution-time.** CardSelection.kt recorded Winding Way as the card-selection
 *    family's last absentee with precisely this diagnosis, and it stayed unencoded through `FW-MODAL`
 *    because modality does not carry it: a [dev.mtgplay.core.definition.SpellMode] is chosen at
 *    CR 601.2b, while the spell is being cast, which is a whole priority round too early. Encoding it as
 *    a mode would have let an opponent see which half they were responding to.
 * 2. **The keep is mandatory and total.** "Put **all** cards of the chosen type … into your hand" leaves
 *    nothing to select, so — unlike [orchestrateLibraryReveal], which loops a keep-one choice — this
 *    flow has exactly one pause, and it is *before* anything is revealed. Reusing the reveal clause with
 *    a large allowance would have enumerated the option of keeping fewer, which the card forbids
 *    (ADR-005).
 *
 * The reveal is public information (CR 701.16a, [GameEvent.CardsRevealed]) and the distribution reuses
 * `LibraryReveal.kt`'s [putRevealedIntoGraveyard] — one implementation of "these revealed cards go to
 * the hand, those to the graveyard", so the two clauses cannot drift on what a CR 400.7 rebirth looks
 * like or on which event narrates it.
 */

/**
 * Runs the chosen-type reveal clause of the resolving [entry] (CR 609.4): pauses for its controller to
 * name one of [clause]'s types, with **nothing yet revealed** — the printed order, and the reason the
 * choice is a gamble rather than a free pick.
 */
internal fun orchestrateChosenTypeReveal(
    state: GameState,
    entry: StackEntry,
    clause: ChosenTypeReveal,
): AdvanceResult {
    val paused =
        state.copy(
            pendingTypeChoice =
                PendingTypeChoice(
                    decider = entry.resolutionController,
                    choices = clause.choices,
                    revealCount = clause.count,
                    sourceCard = entry.resolutionSourceCard,
                ),
        )
    return AdvanceResult.NeedsDecision(paused, pendingTypeChoiceRequest(paused))
}

/**
 * The type choice the open [GameState.pendingTypeChoice] is waiting on (CR 609.4). Pure per ADR-004 and
 * genuinely stateless: the offered types are the clause's own, recorded when the pause opened, and no
 * card has moved.
 */
internal fun pendingTypeChoiceRequest(state: GameState): DecisionRequest.ChooseRevealedCardType {
    val pending = state.pendingTypeChoice ?: error("no card-type choice is pending")
    return DecisionRequest.ChooseRevealedCardType(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        sourceCard = pending.sourceCard,
        revealCount = pending.revealCount,
        options = pending.choices,
    )
}

/**
 * Applies the chosen type (CR 609.4, CR 701.16): reveals the top [PendingTypeChoice.revealCount] cards
 * of the decider's library, puts **every** revealed card matching [chosen] into their hand and every
 * other one into their graveyard, then finishes the resolving object through the shared
 * [completeClauseResolution].
 *
 * There is no second pause. That is the clause's defining property — the partition is fully determined
 * by the type already named, so the whole of the reveal happens inside this one transition and no seat
 * ever sees a state where four cards are revealed and undistributed.
 *
 * A library holding fewer than the reveal count reveals what there is (CR 701.16a); an empty one reveals
 * nothing, emits no event, and is a correct input rather than a special case.
 */
internal fun applyChosenRevealType(
    state: GameState,
    chosen: RevealedCardFilter,
): AdvanceResult {
    val pending = state.pendingTypeChoice ?: error("no card-type choice is pending")
    val entry = resolvingClauseEntry(state)
    val cleared = state.copy(pendingTypeChoice = null)
    val revealed = cleared.player(pending.decider).library.take(pending.revealCount)
    val announced =
        if (revealed.isEmpty()) {
            cleared
        } else {
            cleared.emit(GameEvent.CardsRevealed(pending.decider, revealed.map { it.card }))
        }
    val kept = revealed.filter { matchesFilter(announced, it, chosen) }.map { it.id }.toSet()
    val distributed = putRevealedIntoGraveyard(announced, pending.decider, revealed.map { it.id }, kept)
    return completeClauseResolution(distributed, entry)
}
