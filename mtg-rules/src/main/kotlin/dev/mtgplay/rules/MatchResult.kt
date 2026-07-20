package dev.mtgplay.rules

import dev.mtgplay.core.event.LossReason
import dev.mtgplay.core.identity.PlayerId

/**
 * The outcome of a finished game: in a two-player game, the player who loses causes the other
 * to win (CR 104.2a).
 *
 * Draws (e.g. all remaining players losing simultaneously, CR 104.4a) are deliberately not
 * representable yet; the engine fails loudly if one would occur, and this type grows when a
 * packet actually needs draws.
 *
 * @property winner the winning seat.
 * @property loser the losing seat.
 * @property reason why [loser] lost (CR 104.3).
 */
data class MatchResult(
    val winner: PlayerId,
    val loser: PlayerId,
    val reason: LossReason,
) {
    init {
        require(winner != loser) { "CR 104.2a: a player cannot both win and lose the same game" }
    }
}
