package dev.mtgplay.rules

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingRevealSelection
import dev.mtgplay.core.state.PendingTrigger
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.StackEntry

/**
 * The per-seat filtered view of [state] for [seat] (ADR-007) — the pure derivation of exactly what
 * a player in [seat] may legally see. Beside [pendingRequestOf]: both are pure functions of the
 * state that the protocol layer (ADR-008) transports, never engine behaviour.
 *
 * This is the single, centralized information-hiding boundary (ADR-007): the filtering rules are
 * documented field-by-field on [SeatView], and no consumer is trusted to redact. Read-only —
 * calling it never changes the game; two calls on the same [state]/[seat] are equal.
 *
 * @throws IllegalArgumentException if [seat] is not seated in [state] — a view is only meaningful
 *   for a real seat.
 */
fun viewFor(
    state: GameState,
    seat: PlayerId,
): SeatView {
    require(seat in state.players) { "ADR-007: cannot build a view for unseated seat $seat" }
    val pendingRequest = pendingRequestOf(state)
    return SeatView(
        viewer = seat,
        players = state.players.map { (id, player) -> playerViewOf(id, player, viewer = seat) },
        battlefield = state.sharedZones.battlefield.toList(),
        stack = state.sharedZones.stack.map(::stackEntryViewOf),
        exile = state.sharedZones.exile.toList(),
        turn = state.turn,
        pendingDecision =
            pendingRequest?.let { request ->
                if (request.seat == seat) {
                    DecisionView.ToDecide(request)
                } else {
                    DecisionView.Elsewhere(request.seat, kindOf(request))
                }
            },
        pendingCast = state.pendingCast,
        pendingTriggers = state.pendingTriggers.map(::pendingTriggerViewOf),
        pendingMadness = state.pendingMadness,
        pendingReplacement = state.pendingReplacement,
        pendingMulligan = state.pendingMulligan,
        pendingPlot = state.pendingPlot,
        pendingColorChoice = state.pendingColorChoice,
        pendingActivation = state.pendingActivation,
        pendingReveal = state.pendingRevealSelection?.let { revealViewOf(state, it) },
        pendingOptionalDiscardDraw = state.pendingOptionalDiscardDraw,
        pendingOptionalCostDraw = state.pendingOptionalCostDraw,
        pendingResolutionDiscard = state.pendingResolutionDiscard,
        pendingLibrarySearch = state.pendingLibrarySearch,
    )
}

/** One seat's [PlayerView]: the own hand in full (CR 402), an opponent's as a count only (ADR-007). */
private fun playerViewOf(
    id: PlayerId,
    player: PlayerState,
    viewer: PlayerId,
): PlayerView =
    PlayerView(
        seat = id,
        life = player.life,
        hand =
            if (id == viewer) {
                HandView.Revealed(player.hand.toList())
            } else {
                HandView.Concealed(player.hand.size)
            },
        libraryCount = player.library.size,
        graveyard = player.graveyard.toList(),
        manaPool = player.manaPool.toList(),
        priorityStatus = player.priorityStatus,
        attemptedDrawFromEmptyLibrary = player.attemptedDrawFromEmptyLibrary,
        decisionsAnswered = player.decisionsAnswered,
        drawsThisTurn = player.drawsThisTurn,
    )

/** The public [StackEntryView] of one stack entry (CR 405), dropping the captured definition. */
private fun stackEntryViewOf(entry: StackEntry): StackEntryView =
    when (entry) {
        is StackEntry.Spell ->
            StackEntryView.SpellOnStack(
                objectId = entry.obj.id,
                card = entry.obj.card,
                controller = entry.controller,
                targets = entry.targets.toList(),
            )

        is StackEntry.Ability ->
            StackEntryView.TriggeredAbilityOnStack(
                sourceId = entry.trigger.sourceId,
                sourceCard = entry.trigger.sourceCard,
                controller = entry.trigger.controller,
            )

        is StackEntry.ActivatedAbilityOnStack ->
            StackEntryView.ActivatedAbilityOnStack(
                sourceId = entry.sourceId,
                sourceCard = entry.sourceCard,
                controller = entry.controller,
            )
    }

/** The public [PendingTriggerView] of one fired trigger (CR 603.3), dropping the ability definition. */
private fun pendingTriggerViewOf(trigger: PendingTrigger): PendingTriggerView =
    PendingTriggerView(
        sourceId = trigger.sourceId,
        sourceCard = trigger.sourceCard,
        controller = trigger.controller,
        amount = trigger.amount,
        subject = trigger.subject,
    )

/**
 * Resolves a [PendingRevealSelection] into a [PendingRevealView], exposing the revealed cards — and
 * the keeps gathered so far in a multi-keep clause — to all seats (CR 701.16: the cards are revealed,
 * so public to both). The revealed ids reference the top of the [PendingRevealSelection.decider]'s
 * library; each is resolved to its actual object there.
 */
private fun revealViewOf(
    state: GameState,
    reveal: PendingRevealSelection,
): PendingRevealView {
    val library =
        state.players[reveal.decider]?.library
            ?: error("CR 701.16: a reveal selection names unseated decider ${reveal.decider}")

    fun resolve(id: dev.mtgplay.core.identity.ObjectId) =
        library.firstOrNull { it.id == id }
            ?: error("CR 701.16: revealed id $id is not in ${reveal.decider}'s library")
    return PendingRevealView(
        decider = reveal.decider,
        revealed = reveal.revealedIds.map(::resolve),
        kept = reveal.keptIds.map(::resolve),
    )
}
