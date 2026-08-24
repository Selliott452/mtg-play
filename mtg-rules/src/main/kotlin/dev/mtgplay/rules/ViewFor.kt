package dev.mtgplay.rules

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingRevealSelection
import dev.mtgplay.core.state.PendingTrigger
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.resolutionClauses

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
    // Two steps on purpose: the card table describes exactly the cards the *finished* projection names
    // (docs/design/seat-view-definitions.md §2), so it is derived from the projection rather than from
    // the raw state. The table-less intermediate never escapes this function.
    val projected =
        SeatView(
            viewer = seat,
            cards = emptyMap(),
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
            pendingLibraryLook = state.pendingLibraryLook?.let { lookViewOf(state, it) },
            pendingTriggerTargets = state.pendingTriggerTargets,
            pendingCounterPayment = state.pendingCounterPayment,
        )
    return projected.copy(cards = cardsOf(state.definitions, visibleCardRefs(projected)))
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
                targets = entry.targets.toList(),
            )

        is StackEntry.ActivatedAbilityOnStack ->
            StackEntryView.ActivatedAbilityOnStack(
                sourceId = entry.sourceId,
                sourceCard = entry.sourceCard,
                controller = entry.controller,
                targets = entry.targets.toList(),
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
 * Projects a [PendingLibraryLook] onto its count-only [PendingLibraryLookView] (ADR-007, CR 701.14a) —
 * the deliberate opposite of [revealViewOf]. A reveal resolves its ids to identities because CR 701.16a
 * shows them to everyone; a look resolves nothing, and does not even carry the ids across, because
 * CR 701.14a shows them to the looking player alone (docs/design/library-look.md §3). The source zone is
 * read from the resolving object's clause, which is public — an opponent watches which zone was touched.
 * "Object", not "spell": since `FW-CLAUSEHOOK` a resolving ability carries look clauses too.
 */
private fun lookViewOf(
    state: GameState,
    look: dev.mtgplay.core.state.PendingLibraryLook,
): PendingLibraryLookView {
    val clause =
        state.sharedZones.stack
            .lastOrNull()
            ?.resolutionClauses
            ?.libraryLook
            ?: error("CR 701.14a: a pending look requires a resolving object with a look clause on the stack")
    return PendingLibraryLookView(
        decider = look.decider,
        source = clause.mode.source,
        count = look.poolIds.size,
        awaitingShuffle = look.awaitingShuffle,
    )
}

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
