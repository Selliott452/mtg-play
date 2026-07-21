package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.engine.changeLife
import dev.mtgplay.rules.engine.emit

/**
 * Effect primitive: a source deals [amount] damage to [recipient] (CR 120) — the published
 * building block card resolutions compose (ADR-003; Lightning Bolt is the first client).
 *
 * Damage dealt to a player causes that player to lose that much life as its **result**
 * (CR 120.3a): the transition emits [GameEvent.DamageDealt] and then the life change, which is
 * what keeps damage observably distinct from pure life loss ([loseLife], CR 119.3c) — a
 * distinction later phases' cards depend on (Phyrexian costs pay life without dealing damage;
 * damage triggers care about damage, not life). Zero damage is not dealt at all (CR 120.8):
 * the state returns unchanged, with no event.
 *
 * The recipient is the [Target] sum shape because damage recipients and targetable things
 * coincide in this engine's scope: a player now, battlefield objects from Phase 3 — which adds
 * an object member to [Target] and thereby breaks this function's exhaustive `when` loudly,
 * forcing the marked-damage rules (CR 120.3d) to be implemented rather than approximated.
 *
 * Life may legally drop to 0 or below; the CR 704.5a state-based action ends the game at the
 * next check (CR 704.3) — an effect never ends the game itself.
 */
fun dealDamage(
    state: GameState,
    recipient: Target,
    amount: Int,
): GameState {
    require(amount >= 0) { "CR 120: a damage amount is non-negative, was $amount" }
    if (amount == 0) return state
    return when (recipient) {
        is Target.Player ->
            changeLife(
                state.emit(GameEvent.DamageDealt(recipient, amount)),
                recipient.id,
                -amount,
            )
    }
}
