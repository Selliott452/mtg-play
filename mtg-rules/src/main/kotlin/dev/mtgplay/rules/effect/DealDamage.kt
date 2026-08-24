package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.engine.changeLife
import dev.mtgplay.rules.engine.emit
import dev.mtgplay.rules.engine.markDamage

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
 * coincide in this engine's scope. A [Target.Player] loses life as the damage's result
 * (CR 120.3a). A [Target.Permanent] (P3.1) instead has the damage *marked* on it (CR 120.3d):
 * [GameEvent.DamageDealt] is emitted, but no [GameEvent.LifeChanged] follows — a permanent has no
 * life total — and the mark stays until cleanup (CR 514.2) or a lethal-damage state-based action
 * (CR 704.5g, P3.2) acts on it. This is combat damage's recipient path (CR 510.2) as well as any
 * future permanent-damaging spell's; combat damage does not target (CR 509.1), but recipient and
 * target coincide here, so both reuse [Target.Permanent].
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
        is Target.Permanent ->
            markDamage(
                state.emit(GameEvent.DamageDealt(recipient, amount)),
                recipient.id,
                amount,
            )
        // CR 120.3: damage is dealt to a creature, a player, a planeswalker, or a battle — never to a
        // spell on the stack. No card in the pool can produce this pairing, so it fails loudly rather
        // than guessing (CONVENTIONS.md: never silently approximate).
        is Target.SpellOnStack ->
            error("CR 120.3: damage cannot be dealt to a spell on the stack, got $recipient")
    }
}
