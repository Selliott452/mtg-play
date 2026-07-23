package dev.mtgplay.rules.engine

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.shuffled
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.MulliganStage
import dev.mtgplay.core.state.PendingMulligan
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import kotlinx.collections.immutable.persistentListOf

/*
 * The pre-game London-mulligan phase (CR 103.4/103.5), added in P6.1. Players are processed one at a
 * time in turn order (starting player first): the deciding player repeatedly keeps or mulligans; a
 * mulligan shuffles their hand into their library through the match PRNG (ADR-006) and redraws a full
 * hand; a keep after N mulligans bottoms N cards (in the player's chosen order). When the last player
 * keeps, turn 1 begins. The whole phase's position is [PendingMulligan] on the state, so every pending
 * decision is a pure derivation of the state (ADR-004).
 */

/**
 * Enters the mulligan phase after opening hands are drawn (called by `startGame` when mulligans are
 * enabled): the starting player decides first. The pause is the starting player's keep-or-mulligan.
 */
internal fun enterMulliganPhase(
    state: GameState,
    startingPlayer: PlayerId,
): AdvanceResult {
    val phase = PendingMulligan(startingPlayer, 0, MulliganStage.DECLARE)
    return mulliganPauseOf(state.copy(pendingMulligan = phase))
}

/** The pause at the mulligan decision [state] currently stands on; fails loudly with no pending mulligan. */
internal fun mulliganPauseOf(state: GameState): AdvanceResult {
    val mulligan = state.pendingMulligan ?: error("mulliganPauseOf called on a state with no pending mulligan")
    return AdvanceResult.NeedsDecision(state, pendingMulliganRequest(state, mulligan))
}

/**
 * The pre-game mulligan request the [mulligan] phase currently stands on (CR 103.4/103.5): the
 * deciding player's keep-or-mulligan choice, or — once they have kept after a mulligan — their
 * bottom-cards choice. A pure function of the state, like every pending request (ADR-004).
 */
internal fun pendingMulliganRequest(
    state: GameState,
    mulligan: PendingMulligan,
): DecisionRequest {
    val seat = mulligan.deciding
    val id = DecisionRequestId(seat, state.player(seat).decisionsAnswered)
    return when (mulligan.stage) {
        MulliganStage.DECLARE -> DecisionRequest.ChooseMulligan(id = id, mulligansTaken = mulligan.mulliganCount)
        MulliganStage.BOTTOM -> {
            val hand = state.player(seat).hand
            DecisionRequest.ChooseCardsToBottom(
                id = id,
                options = hand.map { DecisionRequest.ChooseCardsToBottom.Option(it.id, it.card) },
                count = minOf(mulligan.mulliganCount, hand.size),
            )
        }
    }
}

/**
 * Validates a pre-game mulligan decision (CR 103.4/103.5): the keep-or-mulligan single-select, or
 * the bottom-cards multi-select of exactly the required count (Mulligans' half of `validateDecision`).
 */
internal fun validateMulliganDecision(
    request: DecisionRequest.MulliganRequest,
    decision: Decision,
) {
    when (request) {
        is DecisionRequest.ChooseMulligan ->
            validateSingleSelect(request, decision, DecisionRequest.ChooseMulligan.OPTION_COUNT)
        is DecisionRequest.ChooseCardsToBottom -> {
            validateDistinctSubset(request, decision, request.options.size, "bottom")
            val chosen = decision.asMultiSelect(request).indices.size
            require(chosen == request.count) {
                "CR 103.5: exactly ${request.count} card(s) must be bottomed, got $chosen"
            }
        }
    }
}

/**
 * Applies the deciding player's keep-or-mulligan choice (CR 103.4). A mulligan re-pauses on the same
 * player's next keep-or-mulligan; a keep with no mulligans taken finishes them immediately, and a keep
 * after mulligans opens their bottom-cards choice (CR 103.5) — unless their hand is empty, in which
 * case there is nothing to bottom and they finish.
 */
internal fun applyMulliganChoice(
    state: GameState,
    keep: Boolean,
): AdvanceResult {
    val mulligan = state.pendingMulligan ?: error("a mulligan choice was answered with no pending mulligan")
    val seat = mulligan.deciding
    if (!keep) return takeMulligan(state, seat, mulligan.mulliganCount + 1)

    val bottomCount = minOf(mulligan.mulliganCount, state.player(seat).hand.size)
    val kept = state.emit(GameEvent.HandKept(seat, mulligan.mulliganCount))
    return if (bottomCount == 0) {
        finishPlayerMulligan(kept.copy(pendingMulligan = null), seat)
    } else {
        mulliganPauseOf(kept.copy(pendingMulligan = mulligan.copy(stage = MulliganStage.BOTTOM)))
    }
}

/**
 * Applies the deciding player's bottom-cards choice (CR 103.5): the chosen [objectIds] are put on the
 * bottom of their library in selection order, then that player is finished.
 */
internal fun applyBottomChoice(
    state: GameState,
    objectIds: List<ObjectId>,
): AdvanceResult {
    val mulligan = state.pendingMulligan ?: error("a bottom-cards choice was answered with no pending mulligan")
    val seat = mulligan.deciding
    val bottomed = bottomCards(state, seat, objectIds)
    return finishPlayerMulligan(bottomed.copy(pendingMulligan = null), seat)
}

/**
 * Takes a mulligan for [seat] (CR 103.4): shuffles the hand into the library through the match PRNG
 * (ADR-006), redraws a full hand of the same size, records the mulligan as number [newCount], and
 * re-pauses on the same player's next keep-or-mulligan.
 */
private fun takeMulligan(
    state: GameState,
    seat: PlayerId,
    newCount: Int,
): AdvanceResult {
    val player = state.player(seat)
    val redrawSize = player.hand.size
    val (shuffled, nextRng) = player.library.addingAll(player.hand).shuffled(state.rng)
    var current =
        state
            .copy(rng = nextRng)
            .updatePlayer(seat) { it.copy(hand = persistentListOf(), library = shuffled) }
            .emit(GameEvent.MulliganTaken(seat, newCount))
    repeat(redrawSize) { current = drawCard(current, seat) }
    return mulliganPauseOf(current.copy(pendingMulligan = PendingMulligan(seat, newCount, MulliganStage.DECLARE)))
}

/**
 * Puts [objectIds] on the bottom of [seat]'s library in selection order (CR 103.5): each hand object
 * is appended to the end of the library (the bottom, per [dev.mtgplay.core.state.PlayerState]), so the
 * first selected card ends up above the last selected at the bottom. The object keeps its id — a
 * within-pre-game reshuffle like the opening shuffle, not a CR 400.7 zone-change rebirth.
 */
private fun bottomCards(
    state: GameState,
    seat: PlayerId,
    objectIds: List<ObjectId>,
): GameState =
    objectIds.fold(state) { current, id ->
        val hand = current.player(seat).hand
        val index = hand.indexOfFirst { it.id == id }
        require(index >= 0) { "CR 103.5: bottomed object $id is not in $seat's hand" }
        val card = hand[index]
        current
            .updatePlayer(seat) { it.copy(hand = it.hand.removingAt(index), library = it.library.adding(card)) }
            .emit(GameEvent.CardBottomed(seat, card.id, card.card))
    }

/**
 * Finishes [finished]'s mulligans and moves on: the next player in turn order decides next, or — when
 * the starting player would be next again (all players have kept) — the game's first turn begins.
 */
private fun finishPlayerMulligan(
    state: GameState,
    finished: PlayerId,
): AdvanceResult {
    val startingPlayer = state.players.keys.first()
    val next = state.seatAfter(finished)
    return if (next == startingPlayer) {
        beginTurn(state.copy(pendingMulligan = null), state.turn.activePlayer, state.turn.number)
    } else {
        mulliganPauseOf(state.copy(pendingMulligan = PendingMulligan(next, 0, MulliganStage.DECLARE)))
    }
}
