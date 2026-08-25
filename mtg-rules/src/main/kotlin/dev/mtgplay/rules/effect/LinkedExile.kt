package dev.mtgplay.rules.effect

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.recordLinkedExile

/**
 * Effect primitive: exiles the battlefield permanent [objectId] (CR 701.3a) and records the resulting
 * exile object on [sourceId] as **linked information** (CR 607.2) — the published building block a
 * "exile it until this leaves the battlefield" pair composes (ADR-003; Journey to Nowhere is the first
 * client).
 *
 * **What CR 607 actually asks for.** Journey to Nowhere prints two abilities: "When this enchantment
 * enters, exile target creature" and "When this enchantment leaves the battlefield, return **the
 * exiled card**". CR 607.2 makes those two a *linked pair*: the second refers to the objects the first
 * exiled, and to nothing else — not to the last card exiled by anyone, not to every card in exile, and
 * not to the card a *different* Journey to Nowhere exiled. Two Journeys on the battlefield each return
 * their own creature, and this record on the source object is what makes that true by construction.
 *
 * The link is written onto the **source permanent** rather than onto the exiled card, and that is
 * CR 607.2's own direction of reference. It also survives the only event that matters: the source
 * leaving the battlefield is what fires the second ability, and the departure captures this record as
 * last-known information (CR 603.10) into the fired trigger, because by the time that ability resolves
 * there is no permanent left to read it from.
 *
 * A no-op on the recording half if [sourceId] is no longer on the battlefield — the exile still
 * happens, because that is what the first ability was told to do, but there is nothing left to link it
 * to. That is the CR-correct outcome and not a defect: if the Journey has already left the battlefield
 * when its own enters-trigger resolves, its second ability has already fired and found nothing, and the
 * creature stays exiled forever. CR 607.3 is explicit that a linked ability finding no recorded object
 * simply does nothing.
 */
fun exileLinkedToSource(
    state: GameState,
    objectId: ObjectId,
    sourceId: ObjectId,
): GameState {
    val exiled = exilePermanentReturningId(state, objectId)
    return recordLinkedExile(exiled.state, sourceId, exiled.exileId)
}
