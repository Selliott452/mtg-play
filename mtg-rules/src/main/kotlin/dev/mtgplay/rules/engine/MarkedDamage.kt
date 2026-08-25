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
 *
 * **[fromDeathtouchSource] is recorded, not recomputed** (CR 702.2b, CR 704.5h). The CR 704.5h
 * state-based action destroys a creature dealt damage by a source with deathtouch whatever its
 * toughness, so the check needs to know *what dealt* the damage — and [amount] is an [Int] that
 * remembers nothing about its source. By the time the action is checked the source may have left the
 * battlefield (CR 113.7a), so the fact is captured here, where the source is still in hand, and
 * latched onto the object. It only ever latches on: a creature dealt one deathtouch point and then
 * three ordinary ones is still destroyed.
 *
 * @param fromDeathtouchSource whether the source of this damage had deathtouch (CR 702.2), decided by
 *   [sourceHasDeathtouch] at the one place the source is known.
 */
internal fun markDamage(
    state: GameState,
    objectId: ObjectId,
    amount: Int,
    fromDeathtouchSource: Boolean = false,
): GameState {
    require(amount >= 0) { "CR 120.3: a marked-damage amount is non-negative, was $amount" }
    if (amount == 0) return state
    val battlefield = state.sharedZones.battlefield
    val index = battlefield.indexOfFirst { it.id == objectId }
    require(index >= 0) {
        "CR 120.3d: damage is marked on a battlefield permanent, but $objectId is not on the battlefield"
    }
    val obj = battlefield[index]
    val updated =
        obj.copy(
            damageMarked = obj.damageMarked + amount,
            // CR 704.5h latches: once dealt deathtouch damage this turn, always dealt it.
            dealtDeathtouchDamage = obj.dealtDeathtouchDamage || fromDeathtouchSource,
        )
    val marked = battlefield.removingAt(index).addingAt(index, updated)
    val damaged = state.copy(sharedZones = state.sharedZones.copy(battlefield = marked))
    // CR 603.2: "is dealt damage" fires here, the one point damage to a permanent actually lands. Sitting
    // past `dealDamage`'s CR 120.8 zero exit and its CR 615.6 prevention exit is what makes both of those
    // rules apply to the trigger without this function knowing either (`W8-C`).
    return fireEnchantedDamageReceivedTriggers(damaged, objectId)
}
