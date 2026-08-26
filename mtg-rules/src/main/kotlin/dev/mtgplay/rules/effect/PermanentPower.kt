package dev.mtgplay.rules.effect

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.layeredCharacteristics

/**
 * The in-game power of the permanent [objectId] right now (CR 208.1, CR 613), falling back to
 * [lastKnown] when that object is no longer on the battlefield (CR 608.2h) — the published read a
 * resolution uses when its printed line says "**its** power". Boulderbranch Golem's "When this creature
 * enters, you gain life equal to its power" is the first client. Additive (`W9-G`).
 *
 * **The live read is the rule and the fallback is the exception**, which is the order the CR puts them
 * in: *"If an effect requires information about the game, it uses current information … If the object is
 * no longer in the zone it's expected to be in, its last known information is used."* An "equal to its
 * power" clause is evaluated as the ability **resolves**, not as it triggers, so a permanent that has
 * grown or shrunk in the meantime is read at its new size and only a permanent that has *left* reaches
 * [lastKnown].
 *
 * **Why the caller supplies [lastKnown] rather than this function finding it.** The engine keeps no
 * last-known-information store; what it keeps is per-trigger linked information
 * ([dev.mtgplay.core.state.PendingTrigger.amount]), captured where the fact was still true. For an
 * enters-the-battlefield trigger that is the power the permanent entered with, which the detector
 * records; a caller with a better value passes a better value, and the day a general LKI store exists
 * this signature is where it plugs in. Passing the *printed* power would be the wrong fallback and is
 * the mistake this parameter exists to make hard: a prototyped Boulderbranch Golem is a 3/3, and its own
 * card says 6/5.
 *
 * A non-creature permanent has no power (CR 208.1), so an object on the battlefield with no P/T box
 * likewise answers [lastKnown] rather than failing — a resolution that asks about a permanent's power
 * has already been told which permanent, and the ability that asks is printed on a creature.
 */
fun powerOfOrLastKnown(
    state: GameState,
    objectId: ObjectId,
    lastKnown: Int,
): Int {
    val onBattlefield = state.sharedZones.battlefield.any { it.id == objectId }
    if (!onBattlefield) return lastKnown
    return layeredCharacteristics(state, objectId).power ?: lastKnown
}
