package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.random.shuffled
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.MatchConfig
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList

/** Each player's starting life total in the MVP's two-player format (CR 119.1). */
internal const val STARTING_LIFE_TOTAL: Int = 20

/**
 * Starts a game from [config] (the engine's `start`): determines the starting player — the
 * configured seat, or a random draw from the match PRNG (CR 103.1, ADR-006) — shuffles each
 * deck into its library (CR 103.1), draws opening hands (CR 103.5; mulligans deferred to
 * Phase 6), and begins turn 1, advancing to the first pause.
 *
 * Seats are always processed in turn order — starting player first, then ascending-seat
 * wrap-around — never in the config map's own iteration order, so determinism cannot depend on
 * the caller's map implementation.
 */
internal fun startGame(config: MatchConfig): AdvanceResult {
    val seats = config.libraries.keys.sortedBy(PlayerId::seat)
    var rng = Rng(config.seed)
    val startingPlayer =
        config.startingPlayer ?: run {
            val (index, next) = rng.nextInt(seats.size)
            rng = next
            seats[index]
        }
    val startIndex = seats.indexOf(startingPlayer)
    val turnOrder = seats.subList(startIndex, seats.size) + seats.subList(0, startIndex)

    var nextObjectId = 0L
    var players: PersistentMap<PlayerId, PlayerState> = persistentMapOf()
    for (seat in turnOrder) {
        val deck =
            config.libraries.getValue(seat).map { card ->
                GameObject(ObjectId(nextObjectId), card, seat).also { nextObjectId += 1 }
            }
        val (library, nextRng) = deck.toPersistentList().shuffled(rng)
        rng = nextRng
        players =
            players.putting(
                seat,
                PlayerState(
                    life = STARTING_LIFE_TOTAL,
                    library = library,
                    hand = persistentListOf(),
                    graveyard = persistentListOf(),
                ),
            )
    }

    var state =
        GameState(
            players = players,
            turn = Turn(startingPlayer, 1, TurnPhase.BEGINNING, TurnStep.UNTAP),
            sharedZones =
                SharedZones(
                    battlefield = persistentListOf(),
                    stack = persistentListOf(),
                    exile = persistentListOf(),
                ),
            nextObjectId = nextObjectId,
            rng = rng,
            events = persistentListOf(GameEvent.GameStarted(startingPlayer)),
            definitions = canonicalDefinitions(config),
        )
    for (seat in turnOrder) {
        repeat(config.startingHandSize) { state = drawCard(state, seat) }
    }
    return beginTurn(state, startingPlayer, 1)
}

/** Begins [activePlayer]'s turn number [number] (CR 500.1) at the untap step. */
internal fun beginTurn(
    state: GameState,
    activePlayer: PlayerId,
    number: Int,
): AdvanceResult {
    val begun =
        state
            .copy(turn = Turn(activePlayer, number, TurnPhase.BEGINNING, TurnStep.UNTAP))
            .emit(GameEvent.TurnBegan(activePlayer, number))
    return beginPosition(begun)
}

/**
 * Begins the position the state's turn currently stands at: emits the phase/step-began events,
 * performs the position's turn-based actions, and grants priority where the rules grant it —
 * not during untap (CR 502.4), conditionally during cleanup (CR 514.3), and after turn-based
 * actions everywhere else (CR 117.3b). Re-entered for the same position exactly when a step
 * repeats (the CR 514.3a extra cleanup).
 */
internal fun beginPosition(state: GameState): AdvanceResult {
    val position = positionOf(state.turn)
    var current = state
    if (isPhaseInitial(position)) current = current.emit(GameEvent.PhaseBegan(position.phase))
    val step = position.step
    if (step != null) current = current.emit(GameEvent.StepBegan(step))
    return when (step) {
        // CR 505.5: the main phases have no turn-based actions in P1.2's scope.
        null -> grantPriorityRound(current)
        // CR 502.4: no player receives priority during the untap step.
        TurnStep.UNTAP -> advancePastCurrentPosition(untapStepTurnBasedActions(current))
        TurnStep.UPKEEP -> grantPriorityRound(current)
        // CR 504.1: the active player draws, then priority is granted (CR 504.2).
        TurnStep.DRAW -> grantPriorityRound(drawStepTurnBasedAction(current))
        // Combat turn-based actions (declaring attackers/blockers, dealing damage) are Phase 3;
        // in P1.2 the combat steps exist and pass through with normal priority windows.
        TurnStep.BEGINNING_OF_COMBAT -> grantPriorityRound(current)
        TurnStep.DECLARE_ATTACKERS -> grantPriorityRound(current)
        TurnStep.DECLARE_BLOCKERS -> grantPriorityRound(current)
        TurnStep.COMBAT_DAMAGE -> grantPriorityRound(current)
        TurnStep.END_OF_COMBAT -> grantPriorityRound(current)
        TurnStep.END -> grantPriorityRound(current)
        TurnStep.CLEANUP -> cleanupStep(current)
    }
}

/**
 * The cleanup step (CR 514). First turn-based action: the active player discards down to
 * maximum hand size (CR 514.1) — if needed, the engine suspends with the discard request.
 * The step continues in [finishCleanup] once the hand is within bounds.
 */
internal fun cleanupStep(state: GameState): AdvanceResult {
    val active = state.turn.activePlayer
    return if (state.player(active).hand.size > MAXIMUM_HAND_SIZE) {
        AdvanceResult.NeedsDecision(state, cleanupDiscardRequest(state))
    } else {
        finishCleanup(state)
    }
}

/**
 * The rest of the cleanup step after the discard: the simultaneous damage-removal and
 * end-of-effects turn-based actions (CR 514.2, a hook in P1.2), then the CR 514.3 rule —
 * normally no player receives priority and the turn ends, but if state-based actions performed
 * work (or, from Phase 5, triggered abilities are waiting), players do receive priority
 * (CR 514.3a) and, when that round completes, another cleanup step follows (see
 * `endOfPriorityRound`).
 */
internal fun finishCleanup(state: GameState): AdvanceResult {
    val eased = cleanupRemoveDamageAndEndEffects(state)
    return when (val outcome = performStateBasedActions(eased)) {
        is SbaOutcome.Loss -> AdvanceResult.GameOver(outcome.state, outcome.result)
        is SbaOutcome.Continued ->
            if (outcome.performedWork) {
                // CR 514.3a. Unreachable in P1.2 — the only state-based actions are player
                // losses, which take the Loss branch above — but the loop structure is real:
                // the repeat path (priority round in cleanup, then another cleanup step) is
                // exercised structurally by the engine tests.
                grantPriorityRound(outcome.state)
            } else {
                advancePastCurrentPosition(outcome.state)
            }
    }
}

/**
 * Leaves the current position: every mana pool empties because the step or phase is ending
 * (CR 500.4), then the next step or phase in CR 500.1 order begins — or, when the turn's last
 * position just ended, the next player's turn.
 */
internal fun advancePastCurrentPosition(state: GameState): AdvanceResult {
    val eased = emptyManaPoolsAtPositionEnd(state)
    val next = positionAfter(eased.turn)
    return if (next == null) {
        beginTurn(eased, eased.seatAfter(eased.turn.activePlayer), eased.turn.number + 1)
    } else {
        beginPosition(eased.copy(turn = eased.turn.copy(phase = next.phase, step = next.step)))
    }
}

/**
 * The match's definition registry in canonical, name-sorted insertion order (the deterministic
 * iteration rule on [GameState]) — never the config map's own order, so callers may pass any
 * `Map` implementation (ADR-006).
 */
private fun canonicalDefinitions(config: MatchConfig): PersistentMap<CardRef, CardDefinition> {
    var definitions = persistentMapOf<CardRef, CardDefinition>()
    for ((ref, definition) in config.definitions.entries.sortedBy { it.key.name }) {
        definitions = definitions.putting(ref, definition)
    }
    return definitions
}
