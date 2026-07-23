package dev.mtgplay.rules.effect

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.drawCard

/**
 * Effect primitive: [player] draws [count] cards (CR 120.1) — the published building block a draw
 * effect composes (ADR-003; Abundant Growth's enters-the-battlefield "draw a card" is the first
 * client).
 *
 * Cards are drawn one at a time (CR 120.2) through the engine's draw move: each puts the top card of
 * [player]'s library into their hand as a new object (CR 400.7). A draw from an empty library fails
 * and records the CR 704.5c attempt on the player, which the state-based action acts on at the next
 * check — an effect never ends the game itself. Drawing zero cards changes nothing.
 */
fun drawCards(
    state: GameState,
    player: PlayerId,
    count: Int,
): GameState {
    require(count >= 0) { "CR 120.1: a draw count is non-negative, was $count" }
    return (0 until count).fold(state) { current, _ -> drawCard(current, player) }
}
