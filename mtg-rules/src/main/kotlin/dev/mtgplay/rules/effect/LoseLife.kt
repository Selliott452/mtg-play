package dev.mtgplay.rules.effect

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.changeLife

/**
 * Effect primitive: [player] loses [amount] life (CR 119.3c) — the published building block
 * card resolutions compose (ADR-003; the P2.1 fixture spells and, from P2.2, real card
 * definitions in `mtg-cards`).
 *
 * Life may legally drop to 0 or below; the CR 704.5a state-based action ends the game at the
 * next check (CR 704.3), which the engine performs before any player would receive priority —
 * an effect never ends the game itself. Damage is *not* this primitive: damage dealt to a
 * player causes life loss as its result (CR 120.3), and that distinct primitive arrives with
 * real damage semantics in P2.2.
 */
fun loseLife(
    state: GameState,
    player: PlayerId,
    amount: Int,
): GameState {
    require(amount >= 0) { "CR 119.3c: a life loss is non-negative, was $amount" }
    return if (amount == 0) state else changeLife(state, player, -amount)
}
