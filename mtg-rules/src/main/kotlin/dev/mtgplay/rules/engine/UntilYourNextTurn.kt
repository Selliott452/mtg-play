package dev.mtgplay.rules.engine

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.EffectDuration
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import kotlinx.collections.immutable.toPersistentList

/*
 * The turn-based action that ends **"until your next turn"** effects (CR 611.2) — `W11`, the shared
 * half of the Undercity's last two rooms (Arena's goad and Throne of the Dead Three's hexproof grant).
 *
 * Its own file rather than a fourth function in TurnBasedActions.kt because it is a *different* moment
 * in the turn from everything there: the CR 514.2 cleanup ends effects at the **end** of a turn and this
 * one ends them at the **beginning** of one, which is the whole reason the duration could not be
 * expressed before. Keeping the two apart is what stops the next reader assuming there is one
 * end-of-effects action with two callers.
 *
 * **Why the start of the turn and not the cleanup.** "Until your next turn" runs out as that turn
 * begins; the alternative reading — sweeping it at that turn's cleanup, one step later than CR 118.5's
 * "until the *end* of your next turn" wants and a whole turn later than this one does — would leave
 * Throne's creature hexproof for the entirety of the controller's next turn, which is a full turn of
 * removal immunity the card does not grant. Goad happens not to notice the difference in a two-player
 * game (the goaded creature's only combat is on its own controller's turn, and no such turn falls
 * between the two candidate moments), and that near-miss is precisely why the choice is written down
 * here rather than left to whichever card was encoded first.
 */

/**
 * Ends every "until your next turn" effect whose window has run out (CR 611.2), as the turn now
 * beginning begins. Called once from the untap step, before its CR 502.2 turn-based actions, because
 * the untap step is the beginning of the turn (CR 500.1, CR 502).
 *
 * Four things are swept, and they are four because the duration is shared rather than because the rule
 * is: the three timed stores that `when` exhaustively over [EffectDuration], plus the goad markers that
 * carry the same duration on the permanent itself (see [dev.mtgplay.core.state.GameObject.goadedBy]).
 * Each reads the duration's own [EffectDuration.UntilYourNextTurn.hasEnded], so no store can decide the
 * question differently from another or from the acceptance invariant that checks the sweep fired.
 *
 * **This is the only exit, so a missed sweep is a permanent effect rather than a late one.** Nothing
 * else ends the duration — the CR 514.2 cleanup answers `false` for it in all three stores — which is
 * why the sweep is one function at one call site rather than a clause each store performs for itself.
 *
 * No event narrates it, exactly as the CR 514.2 wear-off narrates nothing: it is bookkeeping whose
 * effect is visible in the characteristics and in the attack requirements the very next time either is
 * read.
 */
internal fun endUntilYourNextTurnEffects(state: GameState): GameState {
    val active = state.turn.activePlayer
    val turn = state.turn.number

    fun ended(
        duration: EffectDuration,
        createdOnTurn: Int,
    ): Boolean =
        when (duration) {
            // CR 514.2 owns this one, at the other end of the turn.
            EffectDuration.UntilEndOfTurn -> false
            // CR 611.2b: nothing ends a durationless effect, here least of all.
            EffectDuration.Indefinite -> false
            is EffectDuration.UntilYourNextTurn -> duration.hasEnded(active, turn, createdOnTurn)
        }

    return state
        .copy(
            timedEffects = state.timedEffects.filterNot { ended(it.duration, it.createdOnTurn) }.toPersistentList(),
            preventionEffects =
                state.preventionEffects.filterNot { ended(it.duration, it.createdOnTurn) }.toPersistentList(),
            deathReplacements =
                state.deathReplacements.filterNot { ended(it.duration, it.createdOnTurn) }.toPersistentList(),
        ).updateBattlefield { battlefield ->
            battlefield.map { endExpiredGoad(it, active, turn) }.toPersistentList()
        }
}

/**
 * Clears [obj]'s goad marker if the goading player's next turn has arrived (CR 701.38a), and returns it
 * untouched otherwise.
 *
 * **The marker is cleared rather than left to be re-read, and that is what makes the requirement
 * derivable from one field.** Goad is not stored as a continuous effect — it is a requirement on a
 * declaration, not a characteristic (see [dev.mtgplay.rules.effect.goad]) — so "is this creature
 * goaded?" has to be answerable from the permanent alone at CR 508.1a. Ending the duration by removing
 * the marker at the exact instant it runs out means that question is `goadedBy != null`, with no second
 * derivation to disagree with this one.
 *
 * [activePlayer] and [turnNumber] are threaded in from the caller so the whole sweep asks
 * [EffectDuration.UntilYourNextTurn.hasEnded] with one set of arguments.
 */
private fun endExpiredGoad(
    obj: GameObject,
    activePlayer: PlayerId,
    turnNumber: Int,
): GameObject {
    val goader = obj.goadedBy ?: return obj
    val since = obj.goadedOnTurn ?: error("CR 701.38a: object ${obj.id} is goaded by $goader with no goad turn")
    return if (EffectDuration.UntilYourNextTurn(goader).hasEnded(activePlayer, turnNumber, since)) {
        obj.copy(goadedBy = null, goadedOnTurn = null)
    } else {
        obj
    }
}
