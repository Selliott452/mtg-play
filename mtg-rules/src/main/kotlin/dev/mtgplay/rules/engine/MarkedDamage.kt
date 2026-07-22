package dev.mtgplay.rules.engine

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState

/**
 * Marks [amount] more damage on the battlefield object [objectId] (CR 120.3d): damage dealt to a
 * permanent is *marked* on it, it does not reduce a life total. Additive to any damage already
 * marked this turn — several sources (and the two combat-damage steps first strike creates) mark
 * onto the same object cumulatively — and it stays marked until cleanup wears it off (CR 514.2)
 * or, from P3.2, a lethal-damage state-based action acts on it (CR 704.5g).
 *
 * Fails loudly if [objectId] is not on the battlefield: marked damage is a battlefield-only
 * quantity (CR 120.3, the acceptance invariant checker enforces the scope), so a caller trying to
 * mark elsewhere is an engine defect, never silently tolerated. Zero [amount] is handled by
 * [dev.mtgplay.rules.effect.dealDamage] (CR 120.8) before it reaches here.
 */
internal fun markDamage(
    state: GameState,
    objectId: ObjectId,
    amount: Int,
): GameState {
    require(amount >= 0) { "CR 120.3: a marked-damage amount is non-negative, was $amount" }
    if (amount == 0) return state
    val battlefield = state.sharedZones.battlefield
    val index = battlefield.indexOfFirst { it.id == objectId }
    require(index >= 0) {
        "CR 120.3d: damage is marked on a battlefield permanent, but $objectId is not on the battlefield"
    }
    val obj = battlefield[index]
    val marked = battlefield.removingAt(index).addingAt(index, obj.copy(damageMarked = obj.damageMarked + amount))
    return state.copy(sharedZones = state.sharedZones.copy(battlefield = marked))
}
