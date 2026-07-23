package dev.mtgplay.rules.effect

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.changeLife

/**
 * Effect primitive: [player] gains [amount] life (CR 119.3) — the published building block a lifegain
 * effect composes (ADR-003; Armadillo Cloak's damage-triggered "gain that much life" is the first
 * client).
 *
 * Emits [dev.mtgplay.core.event.GameEvent.LifeChanged] via [changeLife]. Zero life gain changes
 * nothing (the state is returned unchanged). Life gain is not damage and never ends the game; it is
 * the mirror of [loseLife].
 */
fun gainLife(
    state: GameState,
    player: PlayerId,
    amount: Int,
): GameState {
    require(amount >= 0) { "CR 119.3: a life gain is non-negative, was $amount" }
    return if (amount == 0) state else changeLife(state, player, amount)
}
