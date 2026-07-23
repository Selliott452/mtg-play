package dev.mtgplay.rules.effect

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target

/**
 * Effect primitive: a source deals [amount] damage to **each opponent** of [controller] (CR 120,
 * CR 102.1) — the published building block Guttersnipe's "deals 2 damage to each opponent" and Voldaren
 * Epicure's "deals 1 damage to each opponent" compose (ADR-003).
 *
 * Each opponent is dealt the damage separately, in turn order, via [dealDamage] (so each loses that
 * much life, CR 120.3a). In the two-player MVP there is exactly one opponent, but the primitive is
 * written for any number so it generalises unchanged. Zero damage is not dealt at all (CR 120.8);
 * [dealDamage] handles that per recipient. Opponents are every seated player other than [controller].
 */
fun dealDamageToEachOpponent(
    state: GameState,
    controller: PlayerId,
    amount: Int,
): GameState {
    require(amount >= 0) { "CR 120: a damage amount is non-negative, was $amount" }
    return state.players.keys
        .filter { it != controller }
        .fold(state) { current, opponent -> dealDamage(current, Target.Player(opponent), amount) }
}
