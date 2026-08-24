package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingMadness
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import kotlinx.collections.immutable.toPersistentList

/*
 * The madness reflexive-cast flow (CR 702.35b), the resolution half of madness. The discard→exile
 * replacement (Replacements.kt) exiled the card and set up its reflexive trigger; that trigger is
 * placed on the stack like any other (TriggerPlacement.kt) and, when it resolves here, offers its owner
 * a yes/no cast for the card's madness cost. On yes the full CR 601 pipeline runs from exile; on no —
 * or when the cast is impossible — the card is put into its owner's graveyard.
 */

/**
 * Resolves a madness reflexive trigger (CR 702.35b): the ability leaves the stack (CR 113.7a), then, if
 * the madness cast is currently possible, the engine suspends on a yes/no ([GameState.pendingMadness]);
 * otherwise the exiled card is put straight into its owner's graveyard. Called from [resolveAbility]
 * when the resolving ability's condition is [dev.mtgplay.core.definition.TriggerCondition.MadnessCast].
 */
internal fun resolveMadnessTrigger(
    state: GameState,
    entry: StackEntry.Ability,
): AdvanceResult {
    check(state.sharedZones.stack.lastOrNull() == entry) { "CR 608.1: only the topmost stack object may resolve" }
    val trigger = entry.trigger
    val exiledId =
        trigger.subject ?: error("CR 702.35b: a madness reflexive trigger carries its exiled card as its subject")
    val owner = trigger.controller
    val ceased =
        state
            .updateStack { it.removingAt(it.lastIndex) }
            .emit(GameEvent.TriggeredAbilityResolved(owner, trigger.sourceCard))
    val exiled =
        ceased.sharedZones.exile.firstOrNull { it.id == exiledId }
            ?: error("CR 702.35b: the madness card $exiledId is no longer in exile as its trigger resolves")
    val definition = spellDefinitionOf(ceased, exiled.card)
    val permission =
        madnessPermissionOf(ceased, exiled.card)
            ?: error("CR 702.35b: madness card ${exiled.card.name} has no madness casting permission")
    return if (madnessCastViable(ceased, owner, definition, permission, exiledId)) {
        val pending = ceased.copy(pendingMadness = PendingMadness(owner, exiledId))
        AdvanceResult.NeedsDecision(pending, pendingMadnessRequest(pending))
    } else {
        // CR 702.35b: if the card isn't cast this way, its owner puts it into their graveyard.
        grantPriorityRound(putMadnessCardIntoGraveyard(ceased, owner, exiledId))
    }
}

/**
 * The yes/no cast request the open [GameState.pendingMadness] is waiting on (CR 702.35b): the owner may
 * cast the exiled card for its madness cost. A pure function of the state (ADR-004).
 */
internal fun pendingMadnessRequest(state: GameState): DecisionRequest.ChooseYesNo {
    val pending = state.pendingMadness ?: error("no madness cast choice is pending")
    val exiled =
        state.sharedZones.exile.firstOrNull { it.id == pending.exiledObjectId }
            ?: error("CR 702.35b: the pending madness card ${pending.exiledObjectId} is not in exile")
    return DecisionRequest.ChooseYesNo(
        id = DecisionRequestId(pending.owner, state.player(pending.owner).decisionsAnswered),
        prompt = "cast ${exiled.card.name} for its madness cost",
        cardObjectId = pending.exiledObjectId,
        card = exiled.card,
    )
}

/**
 * Applies the owner's yes/no (CR 702.35b): [accept] `true` opens a cast of the exiled card from exile at
 * its madness cost — the normal CR 601 pipeline, the owner holding priority throughout — and [accept]
 * `false` puts the card into its owner's graveyard. Either way the pending madness choice is cleared.
 */
internal fun applyMadnessCastChoice(
    state: GameState,
    accept: Boolean,
): AdvanceResult {
    val pending = state.pendingMadness ?: error("no madness cast choice is pending")
    val cleared = state.copy(pendingMadness = null)
    return if (accept) {
        val permission =
            madnessPermissionOf(cleared, exiledCardOf(cleared, pending))
                ?: error("CR 702.35b: madness card has no madness casting permission at cast time")
        // The reflexive trigger has resolved and the cast is accepted, so the "awaiting madness" marker
        // is cleared now; the card stays in exile only until the cast pipeline moves it to the stack.
        // The owner casts as the trigger resolves; they hold priority for the gathering (CR 601.2).
        val unmarked =
            cleared.updateExile { exile ->
                exile
                    .map { if (it.id == pending.exiledObjectId) it.copy(awaitingMadness = false) else it }
                    .toPersistentList()
            }
        val casting = unmarked.updatePlayer(pending.owner) { it.copy(priorityStatus = PriorityStatus.HOLDS_PRIORITY) }
        beginCastGathering(casting, pending.owner, pending.exiledObjectId, CastSource.EXILE, permission)
    } else {
        grantPriorityRound(putMadnessCardIntoGraveyard(cleared, pending.owner, pending.exiledObjectId))
    }
}

private fun exiledCardOf(
    state: GameState,
    pending: PendingMadness,
) = state.sharedZones.exile
    .firstOrNull { it.id == pending.exiledObjectId }
    ?.card
    ?: error("CR 702.35b: the pending madness card ${pending.exiledObjectId} is not in exile")

/**
 * Puts the exiled madness card [exiledId] into [owner]'s graveyard (CR 702.35b, CR 400.7): it leaves
 * exile as a new object, its [GameObject.awaitingMadness] marker gone, emitting
 * [GameEvent.MadnessCardPutIntoGraveyard]. Fails loudly if the card is not in exile.
 */
internal fun putMadnessCardIntoGraveyard(
    state: GameState,
    owner: PlayerId,
    exiledId: ObjectId,
): GameState {
    val index = state.sharedZones.exile.indexOfFirst { it.id == exiledId }
    require(index >= 0) { "CR 702.35b: madness card $exiledId is not in exile" }
    val exiled = state.sharedZones.exile[index]
    val (graveyardId, allocated) = state.allocateObjectId()
    val reborn = GameObject(id = graveyardId, card = exiled.card, owner = owner)
    return allocated
        .updateExile { it.removingAt(index) }
        .updatePlayer(owner) { it.copy(graveyard = it.graveyard.adding(reborn)) }
        .emit(GameEvent.MadnessCardPutIntoGraveyard(owner, exiledId, exiled.card, graveyardId))
}
