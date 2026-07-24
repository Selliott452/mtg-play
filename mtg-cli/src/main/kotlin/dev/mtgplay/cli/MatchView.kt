package dev.mtgplay.cli

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState

/**
 * One seat's view of the game (P6.4): the [state] to render, the [viewer] seat whose perspective
 * we render from, and the seat display [names].
 *
 * **Hidden-information discipline (ADR-007, deferred to Phase 7).** The formal per-seat filtered
 * state API does not exist yet; the CLI honours the boundary by construction - it renders the
 * [viewer]'s own hand in full but only *counts* for the opponent's hand and both libraries, and it
 * never renders library order. A future filtered-state API replaces this render-time discipline
 * with a state the CLI cannot even see the opponent's hand in.
 *
 * @property state the game state at the current pause.
 * @property viewer the seat the view is rendered for - the seat currently deciding.
 * @property names each seat's display name (deck name); used everywhere a seat is printed.
 */
data class MatchView(
    val state: GameState,
    val viewer: PlayerId,
    val names: Map<PlayerId, String>,
) {
    /** The viewer's single opponent (CR 102.2); the only other seat in a two-player game. */
    val opponent: PlayerId =
        state.players.keys.firstOrNull { it != viewer }
            ?: error("a two-player game always has an opponent for $viewer")

    /** The display name of [seat] - its deck name, falling back to the raw seat index. */
    fun nameOf(seat: PlayerId): String = names[seat] ?: "Player ${seat.seat}"
}
