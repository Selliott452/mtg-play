package dev.mtgplay.rules.engine

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState

/*
 * **Last known information about a departed permanent's power** (CR 608.2h, CR 113.7a), `W9-D`.
 *
 * The engine has always had *some* last-known information, but always captured at the one moment it was
 * needed and carried on the thing that needed it: a fired trigger's [dev.mtgplay.core.state.PendingTrigger]
 * carries its source's identity, damage latches its source's deathtouch onto the recipient, and a
 * [dev.mtgplay.core.state.TimedContinuousEffect] carries a `sourceCard`. Each of those is a *push* — the
 * moment of capture is inside the mechanism that will read it back.
 *
 * Monstrous Emergence is the first card whose read has no such moment. "Damage equal to the power of the
 * creature you chose" is calculated **as the spell resolves** (CR 608.2h), so it must be the live CR 613
 * layered power while that creature is on the battlefield — a Cryoshatter cast in response really does
 * shrink the damage, and a pump really does grow it. But if the creature is killed in response, the same
 * rule falls back to what its power last was, and nothing in the state remembers.
 *
 * So this is a *pull*: every battlefield departure records the leaving permanent's layered power, and a
 * resolution that finds its object gone reads it back. Power alone, because power is the only
 * characteristic any card in the gauntlet asks about a permanent that is no longer there; a card asking for
 * a departed permanent's toughness, colour, or types must widen the record rather than reinterpret this one.
 *
 * **Turn-scoped**, pruned by the CR 514.2 cleanup alongside the three effect stores. No CR 608.2h read in
 * this pool spans a turn boundary — the reader is always a spell or ability that was already on the stack
 * when the object left — so keeping the map beyond the turn would only grow it.
 */

/**
 * Records the layered power (CR 613.3, sublayer 7) of the battlefield permanent [objectId], to be read back
 * after it has left (CR 608.2h). Called from every battlefield departure **before** the object is removed —
 * the layered value cannot be computed once it is gone, which is the whole reason this is captured rather
 * than derived.
 *
 * A no-op for an object that is not on the battlefield or is not a creature: neither has a power to
 * remember, and a stored entry for one would be a value a later reader could take for an answer. An
 * existing entry for the same id is overwritten, which cannot happen — [ObjectId]s are allocated from a
 * strictly monotonic sequence and a returning permanent is a new object (CR 400.7) — and the overwrite is
 * the harmless behaviour if it ever did.
 */
internal fun rememberLastKnownPower(
    state: GameState,
    objectId: ObjectId,
): GameState {
    val permanent = state.sharedZones.battlefield.firstOrNull { it.id == objectId }
    return if (permanent != null && isCreature(state, permanent)) {
        state.copy(lastKnownPower = state.lastKnownPower.putting(objectId, effectivePower(state, objectId)))
    } else {
        state
    }
}

/**
 * The power of [objectId] for a CR 608.2h calculation: its **live** CR 613 layered power while it is on the
 * battlefield, and the last known value recorded by [rememberLastKnownPower] once it has left.
 *
 * The order is the rule, not a fallback preference. While the permanent exists, the answer must be the
 * current one — an effect that changed its power after the reader was put on the stack is exactly what
 * CR 608.2h's "as it resolves" is about. Only when there is no current answer does last known information
 * apply (CR 113.7a).
 *
 * `null` for an object that is neither on the battlefield nor remembered: a caller that reaches that has
 * asked about something that was never a battlefield creature, and deciding what a missing answer means is
 * the caller's — Monstrous Emergence's revealed-card branch never asks this at all.
 */
internal fun powerOnBattlefieldOrLastKnown(
    state: GameState,
    objectId: ObjectId,
): Int? =
    if (state.sharedZones.battlefield.any { it.id == objectId }) {
        effectivePower(state, objectId)
    } else {
        state.lastKnownPower[objectId]
    }

/**
 * The **printed** power of the card [card] (CR 208.1, CR 109.3) — what a card outside the battlefield has,
 * and all it has: no continuous effect in this pool reaches a hand, a graveyard, or a library, so this is
 * both the printed and the current answer for one.
 *
 * Deliberately *not* a route into the CR 613 layer system, which answers only about battlefield objects.
 * Reading a hand card's power is a different question with a different rule, and giving it its own function
 * is what stops the two being confused at a call site.
 *
 * Fails loudly for a card with no definition or no printed power/toughness box: a caller reaches this only
 * for a card the option enumeration already established was a creature card (ADR-005), so either absence is
 * an engine defect.
 */
internal fun printedPowerOf(
    state: GameState,
    card: CardRef,
): Int =
    state.definitions[card]
        ?.characteristics
        ?.powerToughness
        ?.power
        ?: error("CR 208.1: card ${card.name} has no printed power; only a creature card may be a power source")
