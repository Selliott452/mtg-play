package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.state.DamageSource
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.engine.changeLife
import dev.mtgplay.rules.engine.damageIsPrevented
import dev.mtgplay.rules.engine.emit
import dev.mtgplay.rules.engine.markDamage
import dev.mtgplay.rules.engine.sourceHasDeathtouch

/**
 * Effect primitive: [source] deals [amount] damage to [recipient] (CR 120) — the published
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
 *
 * **[source] and the CR 615 prevention step** (`FW-PREVENT`, docs/design/protection.md §3). CR 120.1
 * makes the source half of what damage is, and this primitive had no room for it: combat computed
 * one and dropped it, card resolutions never had one to drop, and the event narrated only the
 * recipient. Prevention is the reason it can no longer be optional — every prevention effect in the
 * CR is a predicate on the *source's* characteristics ("damage from sources with the stated
 * quality", "damage that sources of the colour of your choice would deal"), so the predicate was
 * not expressible at the point where damage happens.
 *
 * The prevention check ([damageIsPrevented]) is therefore the **first** thing this function does
 * after the CR 120.8 zero exit, and its position is the rule: CR 615.6 says prevented damage never
 * happens, so no [GameEvent.DamageDealt] is emitted, no damage is marked, no life is lost, and —
 * because both are results of damage *dealt* — no lifelink gains life (CR 702.15) and no
 * damage-dealt trigger fires. A [GameEvent.DamagePrevented] is emitted in its place as derived
 * observability (PLAN.md §2.2); nothing in the rules reads it.
 *
 * **Fully prevented damage is not the same as zero damage.** Both exit here with the state
 * unchanged, for different reasons — CR 120.8 for one and CR 615.6 for the other — and the events
 * are what tell them apart.
 *
 * @param source what is dealing the damage (CR 120.1); see [DamageSource] for why it carries both
 *   an id and a [dev.mtgplay.core.identity.CardRef].
 */
fun dealDamage(
    state: GameState,
    source: DamageSource,
    recipient: Target,
    amount: Int,
): GameState {
    require(amount >= 0) { "CR 120: a damage amount is non-negative, was $amount" }
    // CR 120.8: zero damage is not dealt at all.
    if (amount == 0) return state
    // CR 615.6: prevented damage never happens — no event, no mark, no life loss, no lifelink, no
    // trigger. This must precede every one of those, which is why it is checked before any of the
    // work below and not applied as a subtraction somewhere upstream.
    return if (damageIsPrevented(state, source, recipient)) {
        state.emit(GameEvent.DamagePrevented(source, recipient, amount))
    } else {
        dealUnpreventedDamage(state, source, recipient, amount)
    }
}

/**
 * The damage itself, once CR 120.8 and CR 615 have both let it through: the event, then its result —
 * life loss for a player (CR 120.3a) or a mark on a permanent (CR 120.3d).
 */
private fun dealUnpreventedDamage(
    state: GameState,
    source: DamageSource,
    recipient: Target,
    amount: Int,
): GameState =
    when (recipient) {
        is Target.Player ->
            changeLife(
                state.emit(GameEvent.DamageDealt(source, recipient, amount)),
                recipient.id,
                -amount,
            )
        is Target.Permanent ->
            // CR 702.2b / CR 704.5h: whether the source has deathtouch is decided here, while the
            // source still exists, and latched onto the recipient — the state-based action that reads
            // it runs later, when the source may be gone (CR 113.7a).
            markDamage(
                state.emit(GameEvent.DamageDealt(source, recipient, amount)),
                recipient.id,
                amount,
                fromDeathtouchSource = sourceHasDeathtouch(state, source),
            )
        // CR 120.3: damage is dealt to a creature, a player, a planeswalker, or a battle — never to a
        // spell on the stack and never to a card in a graveyard. No card in the pool can produce either
        // pairing, so both fail loudly rather than guessing (CONVENTIONS.md: never silently approximate).
        is Target.SpellOnStack ->
            error("CR 120.3: damage cannot be dealt to a spell on the stack, got $recipient")
        is Target.CardInGraveyard ->
            error("CR 120.3: damage cannot be dealt to a card in a graveyard, got $recipient")
    }
