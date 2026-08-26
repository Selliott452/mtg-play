package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.emit
import dev.mtgplay.rules.engine.updateBattlefield

/*
 * Putting counters on a permanent and taking them off (CR 122.1) — the two published primitives a
 * card definition and the CR 704.5q state-based action build on. Added by `FW-COUNTERS`.
 *
 * Counters are pure state: these functions move a multiset entry on the object and emit the event
 * narrating it, and nothing else. What a counter *does* is the layer system's business
 * ([dev.mtgplay.rules.engine.layeredCharacteristics] applies P/T counters in CR 613 sublayer 7c per
 * CR 613.4c, and keyword counters in layer 6 per CR 122.1b), so a counter's effect is always live and
 * never cached — put a `+1/+1` counter on a creature and its toughness is already higher the next
 * time anything reads it, including the state-based-action check that runs before the next priority.
 *
 * Both primitives are battlefield-only and fail loudly elsewhere. CR 122.1 does describe counters on
 * cards in other zones, but nothing in the gauntlet pool puts one there, and
 * [GameObject.counters]'s battlefield scope is an invariant the acceptance checker enforces — so a
 * caller reaching for a graveyard card is a defect, not a feature to accommodate.
 */

/**
 * Puts [amount] counters of kind [counter] on the battlefield permanent [objectId] (CR 122.1),
 * returning the successor state. Emits [GameEvent.CountersPlaced].
 *
 * Additive: the permanent's existing count of that kind goes up by [amount]; a kind it had none of
 * appears. **Nothing is annihilated here** — putting a `-1/-1` counter on a creature that has
 * `+1/+1` counters leaves both on the object, and CR 704.5q removes the matching pairs at the next
 * state-based-action check, which is exactly where the rule puts it (a trigger watching for a
 * counter being placed must see it placed).
 */
fun putCounters(
    state: GameState,
    objectId: ObjectId,
    counter: Counter,
    amount: Int = 1,
): GameState {
    require(amount > 0) { "CR 122.1: putting counters puts at least one, was $amount" }
    val obj = battlefieldPermanent(state, objectId, "put counters on")
    val updated = obj.copy(counters = obj.counters.putting(counter, obj.counterCount(counter) + amount))
    return state
        .replacingBattlefieldObject(updated)
        .emit(GameEvent.CountersPlaced(objectId, obj.card, counter, amount))
}

/**
 * Puts [amount] counters of kind [counter] on the battlefield permanent [objectId] when [amount] is
 * positive, and does nothing at all when it is zero or less (CR 122.1) — the published primitive for a
 * "put counters equal to *something*" effect, where the something is a number the rules let be zero or
 * negative. `W10-C`; Pinnacle Kill-Ship's Station puts "charge counters equal to its power", and a
 * creature's power may be either (CR 208.3).
 *
 * **A primitive rather than an `if` left to the card, because the clamp is a rules judgement and the
 * two mistakes it prevents are both silent.** [putCounters] deliberately *refuses* a non-positive
 * amount: every caller before this one computed a printed constant, so asking for zero counters was an
 * arithmetic defect and failing loudly was right. A power-scaled amount is the first that can legally be
 * zero — station a 0/1 mana dork and the printed line does exactly nothing — so a card composing
 * [putCounters] directly would crash on a legal play. Loosening [putCounters] instead would silence the
 * defect check for every card that still wants it, which is the trade this second function exists to
 * avoid: the loud version stays loud, and the clamped version says so in its name.
 *
 * The clamp is CR 122.1's own reading rather than an invention — there is no such thing as putting zero
 * counters, and a negative count is not an instruction to remove any. It emits nothing when it does
 * nothing, so a trigger watching for counters being placed does not fire on a stationed 0/1.
 */
fun putCountersIfAny(
    state: GameState,
    objectId: ObjectId,
    counter: Counter,
    amount: Int,
): GameState = if (amount <= 0) state else putCounters(state, objectId, counter, amount)

/**
 * Removes [amount] counters of kind [counter] from the battlefield permanent [objectId] (CR 122.1),
 * returning the successor state. Emits [GameEvent.CountersRemoved].
 *
 * Fails loudly if the permanent does not have that many: every caller computes the amount from the
 * counters actually present (CR 704.5q takes the smaller of the two counts), so asking to remove
 * more than exist is an arithmetic defect, not a situation to clamp away. Removing the last counter
 * of a kind drops the entry rather than storing a zero, which is what keeps two states that are the
 * same position comparing equal.
 */
internal fun removeCounters(
    state: GameState,
    objectId: ObjectId,
    counter: Counter,
    amount: Int,
): GameState {
    require(amount > 0) { "CR 122.1: removing counters removes at least one, was $amount" }
    val obj = battlefieldPermanent(state, objectId, "remove counters from")
    val present = obj.counterCount(counter)
    require(present >= amount) {
        "CR 122.1: cannot remove $amount $counter counters from $objectId, which has $present"
    }
    val remaining = present - amount
    val counters = if (remaining == 0) obj.counters.removing(counter) else obj.counters.putting(counter, remaining)
    return state
        .replacingBattlefieldObject(obj.copy(counters = counters))
        .emit(GameEvent.CountersRemoved(objectId, obj.card, counter, amount))
}

/** The battlefield permanent [objectId], or a loud failure naming the [action] that wanted it. */
private fun battlefieldPermanent(
    state: GameState,
    objectId: ObjectId,
    action: String,
): GameObject =
    state.sharedZones.battlefield.firstOrNull { it.id == objectId }
        ?: error("CR 122.1: cannot $action $objectId; only a battlefield permanent carries counters in this pool")

/**
 * [state] with the battlefield entry sharing [updated]'s id replaced by [updated], **in place** —
 * battlefield order is the engine's determinism spine (CR 613.7 timestamps derive from entry order),
 * so a counter change must never reorder the zone.
 */
private fun GameState.replacingBattlefieldObject(updated: GameObject): GameState =
    updateBattlefield { battlefield ->
        val index = battlefield.indexOfFirst { it.id == updated.id }
        battlefield.removingAt(index).addingAt(index, updated)
    }
