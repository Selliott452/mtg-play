package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.ReplacementEffect
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingReplacement
import dev.mtgplay.core.state.PendingTrigger
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId

/*
 * The replacement-effect framework (CR 614/616): an event is proposed, the applicable replacements are
 * gathered at its interception point, and — if two or more apply — the affected player chooses one to
 * apply first (CR 616.1); each applies at most once per event (CR 614.5). The two events with an
 * interception point in the MVP pool are the discard (here — the madness CR 702.35a "exile instead")
 * and a spell leaving the stack (StackResolution.kt — the flashback CR 702.34e "exile instead").
 *
 * The single-replacement path (every real madness/flashback card) is a pure modification; only the
 * fixture-only two-or-more case suspends for the CR 616.1 choice.
 */

/** The [TriggeredAbility.effect] of a synthesized madness reflexive trigger is never resolved. */
private val UNRESOLVED_MADNESS_EFFECT =
    dev.mtgplay.core.definition.ResolutionEffect { _, _ ->
        error("CR 702.35b: a madness reflexive ability's effect is never resolved; the may-cast is the engine's")
    }

/** The outcome of beginning a discard (CR 701.8): either the event completed, or a CR 616.1 choice is due. */
internal sealed interface DiscardOutcome {
    /** The discard (or its replacement) was performed; [state] is the result. */
    data class Completed(
        val state: GameState,
    ) : DiscardOutcome

    /**
     * Two or more replacements apply to this discard (CR 616.1); [state] carries the
     * [GameState.pendingReplacement] and the affected player must choose which to apply first. The card
     * is still in their hand.
     */
    data class NeedsReplacementChoice(
        val state: GameState,
    ) : DiscardOutcome
}

/**
 * The discard→exile replacements (CR 702.35a) that would modify [card] being discarded — the
 * [ReplacementEffect.DiscardToExileInstead]s its definition carries. Empty for a card with no madness.
 */
private fun discardToExileReplacementsOf(
    state: GameState,
    card: CardRef,
): List<ReplacementEffect.DiscardToExileInstead> =
    (state.definitions[card] as? SpellDefinition)
        ?.replacementEffects
        ?.filterIsInstance<ReplacementEffect.DiscardToExileInstead>()
        .orEmpty()

/**
 * Begins discarding the hand object [objectId] of [player] (CR 701.8), applying the CR 614/616
 * replacement framework: with no applicable replacement the card is put into the graveyard; with
 * exactly one it is exiled instead (CR 702.35a) and its reflexive trigger set up; with two or more the
 * affected player must order them (CR 616.1) and the engine suspends.
 */
internal fun beginDiscard(
    state: GameState,
    player: PlayerId,
    objectId: ObjectId,
): DiscardOutcome {
    val card = handCardOf(state, player, objectId)
    val replacements = discardToExileReplacementsOf(state, card.card)
    return when {
        replacements.isEmpty() -> DiscardOutcome.Completed(discardCard(state, player, objectId))
        // CR 614.5: exactly one applies, applied once — exile instead of discarding.
        replacements.size == 1 -> DiscardOutcome.Completed(applyDiscardToExile(state, player, objectId))
        // CR 616.1: two or more; the affected player chooses which to apply first.
        else ->
            DiscardOutcome.NeedsReplacementChoice(
                state.copy(pendingReplacement = PendingReplacement(player, objectId)),
            )
    }
}

/**
 * Applies replacements to a discard where the caller cannot handle a CR 616.1 choice (a resolution
 * effect's "discard a card", which is pure): fails loudly if two or more replacements would apply,
 * which no real MVP card produces. The madness single-replacement path is pure, so this succeeds.
 */
internal fun discardApplyingReplacements(
    state: GameState,
    player: PlayerId,
    objectId: ObjectId,
): GameState =
    when (val outcome = beginDiscard(state, player, objectId)) {
        is DiscardOutcome.Completed -> outcome.state
        is DiscardOutcome.NeedsReplacementChoice ->
            error("CR 616.1: discard of $objectId has two or more replacements; a pure effect cannot order them")
    }

/** The hand object [objectId] of [player]; fails loudly if it is not in hand. */
private fun handCardOf(
    state: GameState,
    player: PlayerId,
    objectId: ObjectId,
): GameObject =
    state.player(player).hand.firstOrNull { it.id == objectId }
        ?: error("object $objectId is not in player $player's hand")

/**
 * Applies the CR 702.35a discard→exile replacement to [objectId]: the card is exiled instead of
 * discarded, as a new object (CR 400.7) marked [GameObject.awaitingMadness], and the reflexive "you may
 * cast it" ability (CR 702.35b) is set up as a fired trigger functioning from exile. Fails loudly if the
 * card has no madness permission — the replacement and the reflexive cast are declared together.
 */
internal fun applyDiscardToExile(
    state: GameState,
    player: PlayerId,
    objectId: ObjectId,
): GameState {
    val card = handCardOf(state, player, objectId)
    requireNotNull(madnessPermissionOf(state, card.card)) {
        "CR 702.35: a discard→exile replacement on ${card.card.name} requires a madness casting permission"
    }
    val handIndex = state.player(player).hand.indexOfFirst { it.id == objectId }
    val (exileId, allocated) = state.allocateObjectId()
    val exiled = GameObject(id = exileId, card = card.card, owner = player, awaitingMadness = true)
    val moved =
        allocated
            .updatePlayer(player) { it.copy(hand = it.hand.removingAt(handIndex)) }
            .updateExile { it.adding(exiled) }
            .emit(GameEvent.CardExiledByMadness(player, exileId, card.card))
    // CR 702.35b: the reflexive "you may cast it" ability fires now, functioning from exile.
    return enqueuePendingTrigger(moved, madnessReflexiveTrigger(exileId, card.card, player))
}

/** The synthesized madness reflexive triggered ability (CR 702.35b), functioning from exile. */
private fun madnessReflexiveTrigger(
    exileId: ObjectId,
    card: CardRef,
    owner: PlayerId,
): PendingTrigger =
    PendingTrigger(
        sourceId = exileId,
        sourceCard = card,
        controller = owner,
        ability =
            TriggeredAbility(
                condition = TriggerCondition.MadnessCast,
                effect = UNRESOLVED_MADNESS_EFFECT,
                zoneScope = TriggerZoneScope.Exile,
            ),
        subject = exileId,
    )

/**
 * The CR 616.1 replacement request the open [dev.mtgplay.core.state.GameState.pendingReplacement] is
 * waiting on: the affected player chooses which of the still-applicable replacements to apply first. A
 * pure function of the state (ADR-004) — the applicable replacements are re-derived from the card,
 * which is still in hand.
 */
internal fun pendingReplacementRequest(state: GameState): DecisionRequest.ChooseReplacement {
    val pending = state.pendingReplacement ?: error("no replacement choice is pending")
    val card = handCardOf(state, pending.player, pending.objectId)
    val replacements = discardToExileReplacementsOf(state, card.card)
    return DecisionRequest.ChooseReplacement(
        id = DecisionRequestId(pending.player, state.player(pending.player).decisionsAnswered),
        options =
            replacements.map {
                DecisionRequest.ChooseReplacement.Option("CR 702.35a: exile ${card.card.name} instead of discarding")
            },
    )
}

/**
 * Applies the affected player's CR 616.1 choice: the chosen discard→exile is applied, clearing the
 * pending choice, then the cleanup step continues (the only origin of a two-or-more discard is the
 * cleanup discard). The chosen index is validated by the engine but does not change the outcome — every
 * applicable replacement in the MVP pool is the same exile-instead — and re-checking for still-applicable
 * replacements is a no-op here, the card now being in exile rather than discarded (CR 614.5). When a
 * genuinely distinct pair of discard replacements exists, the index selects which member to apply and
 * this dispatches on it.
 */
internal fun applyChosenReplacement(state: GameState): AdvanceResult {
    val pending = state.pendingReplacement ?: error("no replacement choice is pending")
    val applied = applyDiscardToExile(state.copy(pendingReplacement = null), pending.player, pending.objectId)
    return cleanupStep(applied)
}
