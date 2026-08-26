package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.definition.ExploreDestination
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingExplore
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.resolutionController
import dev.mtgplay.core.state.resolutionSourceCard
import dev.mtgplay.core.state.resolutionTargets
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.effect.putCounters

/*
 * The explore keyword action (CR 701.40a) — the Map token's *"Target creature you control explores."*
 * Additive (`W10-D`), a member of the `FW-CLAUSEHOOK` family: orchestrate → request → apply.
 *
 * **A branch first and a decision second**, which is what makes it different in shape from every clause
 * beside it. Its three outcomes are:
 *
 * | Top of library | Counter | Card ends | Pause |
 * |---|---|---|---|
 * | a land card | no | in the hand | **none** |
 * | any other card | yes | top of library **or** graveyard | one, two options |
 * | nothing (empty library) | yes | nowhere — none was revealed | **none** |
 *
 * Two of the three run to completion without asking anything, and both silences are load-bearing.
 * Offering "top or graveyard" about a land card already in a hand would be an enumerated illegal action
 * (ADR-005); and an empty library is the case a reader gets wrong by treating the reveal as a
 * precondition — CR 701.40a reveals *if it can*, and with nothing revealed no **land** card was revealed,
 * so the "otherwise" arm runs and the permanent still gets its counter.
 *
 * **The pause discloses a library card, and that is the whole ADR-007 story.** CR 701.40a says *reveal*,
 * so the card is public to both seats; it has not moved anywhere, so it is public *while sitting in a
 * library*, the one zone [dev.mtgplay.rules.SeatView] otherwise never discloses. The reveal selection
 * (CR 701.16) is the only precedent, and [dev.mtgplay.rules.PendingExploreView] follows it exactly.
 */

/**
 * Runs the explore clause of the resolving [entry] (CR 701.40a) against its single permanent target.
 *
 * The deciding seat is the **exploring permanent's controller**, read from the permanent (CR 701.40a
 * names it that way) rather than from the resolving object's controller. For the Map token they are the
 * same seat — its ability targets a creature *you* control — but the two are not the same question and
 * the CR asks the one this reads. CR 608.2b has just re-checked the target, so a permanent that is not on
 * the battlefield is an engine defect and fails loudly.
 */
internal fun orchestrateExplore(
    state: GameState,
    entry: StackEntry,
): AdvanceResult {
    val target =
        entry.resolutionTargets.singleOrNull() as? Target.Permanent
            ?: error("CR 701.40a: an explore clause needs exactly one permanent target, got ${entry.resolutionTargets}")
    val exploring =
        state.sharedZones.battlefield
            .firstOrNull { it.id == target.id }
            ?: error("CR 608.2b: ${entry.resolutionSourceCard.name}'s target ${target.id} is not on the battlefield")
    // CR 701.40a: "that permanent's controller"; control is ownership in the MVP pool.
    val decider = exploring.owner
    val revealed = state.player(decider).library.firstOrNull()
    val announced =
        if (revealed == null) state else state.emit(GameEvent.CardsRevealed(decider, listOf(revealed.card)))
    return if (revealed != null && isLandCard(state, revealed)) {
        // CR 701.40a: a land card goes to the hand and nothing else happens — no counter, and no pause,
        // because there is no second sentence left to answer (ADR-005).
        completeClauseResolution(
            putRevealedIntoGraveyard(announced, decider, listOf(revealed.id), keep = setOf(revealed.id)),
            entry,
        )
    } else {
        exploreOtherwiseArm(announced, entry, decider, exploring, revealed)
    }
}

/**
 * CR 701.40a's "otherwise" arm: the counter goes on [exploring] **first**, in the CR's own order, and
 * then either the pause opens for a revealed nonland card or — with an empty library, where nothing was
 * revealed — the resolution simply finishes with the counter placed.
 */
private fun exploreOtherwiseArm(
    state: GameState,
    entry: StackEntry,
    decider: PlayerId,
    exploring: GameObject,
    revealed: GameObject?,
): AdvanceResult {
    val counted = putCounters(state, exploring.id, Counter.PLUS_ONE_PLUS_ONE)
    if (revealed == null) return completeClauseResolution(counted, entry)
    val paused =
        counted.copy(
            pendingExplore =
                PendingExplore(
                    decider = decider,
                    exploring = exploring.id,
                    revealed = revealed.id,
                    sourceCard = entry.resolutionSourceCard,
                ),
        )
    return AdvanceResult.NeedsDecision(paused, pendingExploreRequest(paused))
}

/**
 * The destination request the open [GameState.pendingExplore] is waiting on (CR 701.40a). Pure per
 * ADR-004: the two destinations are a closed vocabulary, and the revealed card and the exploring
 * permanent are read back out of the state the pause was opened against.
 */
internal fun pendingExploreRequest(state: GameState): DecisionRequest.ChooseExploreDestination {
    val pending = state.pendingExplore ?: error("no explore is pending")
    val entry = resolvingClauseEntry(state)
    val revealed =
        state
            .player(pending.decider)
            .library
            .firstOrNull { it.id == pending.revealed }
            ?: error("CR 701.40a: the revealed card ${pending.revealed} is no longer in ${pending.decider}'s library")
    val exploring =
        state.sharedZones.battlefield
            .firstOrNull { it.id == pending.exploring }
            ?: error("CR 701.40a: the exploring permanent ${pending.exploring} is not on the battlefield")
    return DecisionRequest.ChooseExploreDestination(
        id = DecisionRequestId(pending.decider, state.player(pending.decider).decisionsAnswered),
        controller = entry.resolutionController,
        sourceCard = pending.sourceCard,
        exploring = pending.exploring,
        exploringCard = exploring.card,
        revealedCard = revealed.card,
        options = ExploreDestination.entries.toList(),
    )
}

/**
 * Applies the chosen destination (CR 701.40a) and finishes the resolution.
 *
 * [ExploreDestination.LIBRARY_TOP] moves nothing: the card was never taken out of the library, so
 * "put it back on top" is already true of the state and writing a removal-then-insert would allocate a
 * new object id for a card that never changed zones (CR 400.7 applies to a *move*, and this is not one).
 * [ExploreDestination.GRAVEYARD] is the move, and it goes through the same CR 400.7 mover the reveal
 * selection uses.
 */
internal fun applyExplore(
    state: GameState,
    destination: ExploreDestination,
): AdvanceResult {
    val pending = state.pendingExplore ?: error("no explore is pending")
    val entry = resolvingClauseEntry(state)
    val cleared = state.copy(pendingExplore = null)
    val placed =
        when (destination) {
            ExploreDestination.LIBRARY_TOP -> cleared
            ExploreDestination.GRAVEYARD ->
                putRevealedIntoGraveyard(cleared, pending.decider, listOf(pending.revealed), keep = emptySet())
        }
    return completeClauseResolution(placed, entry)
}

/**
 * Whether the library card [obj] is a **land card** (CR 305.1, CR 701.40a) — read from its printed
 * characteristics, because CR 613's layer system does not reach a library (CR 109.3).
 */
private fun isLandCard(
    state: GameState,
    obj: GameObject,
): Boolean =
    CardType.LAND in
        state.definitions[obj.card]
            ?.characteristics
            ?.cardTypes
            .orEmpty()
