package dev.mtgplay.rules.engine

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.event.LossReason
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.MatchResult

/**
 * One applicable state-based action (CR 704.5): something the game itself does the moment its
 * condition holds, checked whenever a player would receive priority (CR 704.3).
 *
 * Sealed so the performer handles every kind exhaustively; Phase 3+ adds members (aura legality
 * 704.5m/n, …) next to the checks that detect them, without reshaping the check-and-repeat loop.
 */
internal sealed interface StateBasedAction {
    /**
     * [player] loses the game for [reason]: life 0 or less (CR 704.5a) or an attempted draw
     * from an empty library since the last check (CR 704.5c).
     */
    data class PlayerLoses(
        val player: PlayerId,
        val reason: LossReason,
    ) : StateBasedAction

    /**
     * The creature [objectId] dies (CR 704.5f/g): it is put from the battlefield into its owner's
     * graveyard. [cause] distinguishes the two conditions — lethal marked damage (destruction) from
     * zero-or-less toughness (not destruction) — see [CreatureDeathCause]. Added in P3.2.
     */
    data class CreatureDies(
        val objectId: ObjectId,
        val cause: CreatureDeathCause,
    ) : StateBasedAction
}

/**
 * All state-based actions applicable to [state] right now (CR 704.5), in deterministic order:
 * player losses first, in seat order, then creature deaths in battlefield order. Later phases
 * append checks here.
 */
internal fun applicableStateBasedActions(state: GameState): List<StateBasedAction> =
    buildList {
        for ((seat, playerState) in state.players) {
            if (playerState.life <= 0) {
                // CR 704.5a: a player with 0 or less life loses the game.
                add(StateBasedAction.PlayerLoses(seat, LossReason.LIFE_TOTAL_ZERO_OR_LESS))
            }
            if (playerState.attemptedDrawFromEmptyLibrary) {
                // CR 704.5c: a player who attempted to draw from an empty library since the
                // last check loses the game. Acts on the recorded attempt, not on emptiness.
                add(StateBasedAction.PlayerLoses(seat, LossReason.ATTEMPTED_DRAW_FROM_EMPTY_LIBRARY))
            }
        }
        for (obj in state.sharedZones.battlefield) {
            if (!isCreature(state, obj)) continue
            val toughness = effectiveToughness(state, obj.id)
            when {
                // CR 704.5f: toughness 0 or less — graveyard, and it takes precedence over
                // CR 704.5g, which only ever applies to a creature with toughness greater than 0.
                toughness <= 0 ->
                    add(StateBasedAction.CreatureDies(obj.id, CreatureDeathCause.ZERO_OR_LESS_TOUGHNESS))
                // CR 704.5g: toughness greater than 0 and marked damage at least equal to it —
                // lethal damage, destroyed. (Marked damage is then necessarily positive.)
                obj.damageMarked >= toughness ->
                    add(StateBasedAction.CreatureDies(obj.id, CreatureDeathCause.LETHAL_DAMAGE))
            }
        }
    }

/** The result of one full state-based-action check (the CR 704.3 repeat-until-quiet loop). */
internal sealed interface SbaOutcome {
    /**
     * No further state-based actions apply; play proceeds. [performedWork] records whether any
     * check in the loop performed anything — the fact CR 514.3a's cleanup rule branches on.
     */
    data class Continued(
        val state: GameState,
        val performedWork: Boolean,
    ) : SbaOutcome

    /** A player lost; the game is over (CR 104.2a). [state] already carries the closing events. */
    data class Loss(
        val state: GameState,
        val result: MatchResult,
    ) : SbaOutcome
}

/**
 * Performs state-based actions until none apply (CR 704.3): each iteration collects every
 * applicable action, performs the batch simultaneously (CR 704.3), and checks again. A player loss
 * ends the loop — and the game — immediately (CR 104.2a); a creature-death batch feeds the loop's
 * next iteration, so a death that itself creates a new applicable action (none does in the P3.2
 * pool, but a chain is legal) is caught on the following pass.
 */
internal fun performStateBasedActions(state: GameState): SbaOutcome {
    var current = SbaOutcome.Continued(state, performedWork = false)
    while (true) {
        val actions = applicableStateBasedActions(current.state)
        if (actions.isEmpty()) return current
        when (val batch = performBatch(current.state, actions)) {
            is SbaOutcome.Loss -> return batch
            is SbaOutcome.Continued -> current = SbaOutcome.Continued(batch.state, performedWork = true)
        }
    }
}

/**
 * Performs one batch of applicable state-based actions simultaneously (CR 704.3). Player losses
 * are resolved first: a loss ends the game (CR 104.2a), so any simultaneous creature deaths are
 * moot and left unperformed. With no loss this batch, every applicable action is a creature death
 * (CR 704.5f/g) — performed together as one move ([performCreatureDeaths]).
 */
private fun performBatch(
    state: GameState,
    actions: List<StateBasedAction>,
): SbaOutcome {
    val losses = mutableListOf<StateBasedAction.PlayerLoses>()
    val deaths = mutableListOf<StateBasedAction.CreatureDies>()
    for (action in actions) {
        // Exhaustive over the sealed hierarchy: a new state-based-action kind must be sorted here.
        when (action) {
            is StateBasedAction.PlayerLoses -> losses += action
            is StateBasedAction.CreatureDies -> deaths += action
        }
    }
    if (losses.isNotEmpty()) return performPlayerLoss(state, losses)
    return SbaOutcome.Continued(
        performCreatureDeaths(state, deaths.map(StateBasedAction.CreatureDies::objectId)),
        performedWork = true,
    )
}

/**
 * Resolves the player-loss actions of a batch. Exactly one loser ends the game (CR 104.2a).
 *
 * **CR 104.4a draw verdict (P3.2).** Two players losing at the same check would be a draw — which
 * [MatchResult] deliberately cannot represent — so this fails loudly rather than guess a winner. It
 * stays a loud, unreachable corner in the P3.2 pool: no effect damages or drains *both* players at
 * once (combat damages only the defending player; a Bolt hits one target; there is no life
 * payment anywhere in the pool, so no cost drives a second player to 0 alongside the first), and
 * draws happen one player at a time (only the active player draws). A draw first becomes
 * constructible when a symmetric life-loss effect or a life-paying cost joins the pool; the
 * representation lands with it, tested, rather than speculatively now.
 */
private fun performPlayerLoss(
    state: GameState,
    losses: List<StateBasedAction.PlayerLoses>,
): SbaOutcome {
    val losers = losses.distinctBy(StateBasedAction.PlayerLoses::player)
    if (losers.size > 1) {
        error(
            "CR 104.4a: players ${losers.map(StateBasedAction.PlayerLoses::player)} would lose " +
                "simultaneously (a draw); the P3.2 pool cannot construct this and draws are unrepresentable",
        )
    }
    val loss =
        losers.firstOrNull()
            ?: error("performPlayerLoss requires at least one loss, got $losses")
    return loseGame(state, loss.player, loss.reason)
}

/**
 * Ends the game with [loser] losing for [reason]: in a two-player game the losing player's only
 * opponent wins (CR 104.2a). Emits [GameEvent.PlayerLost] and [GameEvent.GameEnded].
 */
internal fun loseGame(
    state: GameState,
    loser: PlayerId,
    reason: LossReason,
): SbaOutcome.Loss {
    val winner =
        state.players.keys.singleOrNull { it != loser }
            ?: error("CR 104.2a: exactly one opponent must remain; games with more seats are unsupported in P1.2")
    val final =
        state
            .emit(GameEvent.PlayerLost(loser, reason))
            .emit(GameEvent.GameEnded(winner, loser))
    return SbaOutcome.Loss(final, MatchResult(winner, loser, reason))
}
