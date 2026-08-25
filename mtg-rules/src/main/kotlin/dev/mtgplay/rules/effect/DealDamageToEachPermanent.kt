package dev.mtgplay.rules.effect

import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.engine.isCreature
import dev.mtgplay.rules.engine.layeredCharacteristics

/**
 * Effect primitive: a source deals [amount] damage to **each** battlefield permanent the [affected]
 * predicate accepts (CR 120) — the published building block a sweeper's resolution composes (ADR-003;
 * Breath Weapon's "each non-Dragon creature" and End the Festivities' "each creature and planeswalker
 * they control" are the first clients).
 *
 * **The affected set is fixed once (CR 608.2).** The predicate is evaluated against the state as the
 * effect begins, and the resulting recipients are then damaged; nothing a later recipient's damage does
 * can add to or remove from the set. Damage from one source at one time is dealt simultaneously
 * (CR 120.6), and marked damage accumulates order-independently (CR 120.3d), so the fold below is
 * observably simultaneous — the same shape [dealDamageToEachOpponent] uses.
 *
 * Each recipient is damaged through [dealDamage], so a permanent has the damage *marked* on it
 * (CR 120.3d) and nothing dies here: the lethal-damage state-based action (CR 704.5g) acts at the next
 * check, after the spell finishes resolving. Zero damage is not dealt at all (CR 120.8).
 *
 * The predicate is the card's, never this primitive's: `mtg-rules` names no specific card (PLAN.md §3),
 * so "non-Dragon", "you control", and every other printed qualifier is card-definition data. The one
 * rules judgement a sweeper needs is published beside it as [isCreaturePermanent].
 */
fun dealDamageToEachPermanent(
    state: GameState,
    amount: Int,
    affected: (GameState, GameObject) -> Boolean,
): GameState {
    require(amount >= 0) { "CR 120: a damage amount is non-negative, was $amount" }
    if (amount == 0) return state
    // CR 608.2: the set of affected objects is determined as the effect is applied, from this state.
    val recipients =
        state.sharedZones.battlefield
            .filter { affected(state, it) }
            .map { it.id }
    return recipients.fold(state) { current, id -> dealDamage(current, Target.Permanent(id), amount) }
}

/**
 * Whether the battlefield object [obj] is a creature right now (CR 302.1) — the published read a
 * card's affected-set predicate uses, delegating to the one in-engine answer combat and the
 * state-based actions already read (so a type-changing effect, when layer 4 arrives, changes both at
 * once). An object with no definition in the registry is inert and is not a creature.
 */
fun isCreaturePermanent(
    state: GameState,
    obj: GameObject,
): Boolean = isCreature(state, obj)

/**
 * Whether the battlefield object [obj] has flying right now (CR 702.9) — the published read a sweeper
 * that spares or targets fliers needs (Krark-Clan Shaman's "each creature **without flying**"). It is
 * the *effective* keyword set, so a layer-6 grant counts and a printed keyword lost to one does not,
 * which is the same answer the combat engine's blocking restrictions read. An object with no definition
 * in the registry is inert and has no keywords.
 *
 * Published beside [isCreaturePermanent] and for the same reason: `mtg-rules` names no specific card
 * (PLAN.md §3), so "without flying" stays card-definition data — the rules module supplies only the one
 * judgement the predicate cannot make for itself.
 */
fun hasFlyingPermanent(
    state: GameState,
    obj: GameObject,
): Boolean = Keyword.FLYING in layeredCharacteristics(state, obj.id).keywords
