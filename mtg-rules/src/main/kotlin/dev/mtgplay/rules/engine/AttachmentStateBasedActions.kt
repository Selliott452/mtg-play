package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.EnchantRestriction
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState

/*
 * The **dangling-attachment** state-based actions (CR 704.5m, CR 704.5n, CR 702.103c): one condition
 * asked three times, with three different answers. Split out of StateBasedActions.kt by `W10-C`, when
 * bestow added the third and pushed both that function's complexity and that file's function count past
 * detekt's budgets — split rather than suppressed, along the seam the CR already draws.
 *
 * StateBasedActions.kt keeps the **spine**: which actions exist, how a batch is sorted, and the CR 704.3
 * repeat-until-quiet loop. This file owns the one question those three actions share — "is this
 * attachment still legal?" — and the collector that turns three answers into three different actions.
 * The per-kind legality predicates live with their own kind (`EquipmentAttachment.kt`,
 * `BestowAttachment.kt`), so each one's rule and its outcome can be read together.
 */

/**
 * The **dangling-attachment** state-based actions applicable to [state], in battlefield order: one rule
 * per attachment kind, all three asking the same question and giving three different answers.
 *
 * - **CR 704.5m** — an Aura attached to an illegal object or to nothing is put into its owner's
 *   graveyard. Two reachable cases: a gone enchanted object (its creature died), and — since
 *   `FW-PROTECT` — a still-present object with protection from the Aura's own quality (CR 702.16c).
 * - **CR 704.5n** — an Equipment attached to an illegal permanent becomes unattached and **stays on the
 *   battlefield**: the opposite outcome on the same condition (`FW-EQUIP`).
 * - **CR 702.103c** — a permanent with bestow attached to an illegal object becomes unattached and
 *   stays, and then stops being an Aura because its type-changing static ability's condition has failed
 *   (`W10-C`). That is also why `enchantRestrictionOf` refuses to call a bestowed permanent an Aura: the
 *   first rule would otherwise reach it first and put it in a graveyard.
 *
 * The three sets are disjoint by construction — an Aura is not an Equipment, and a bestowed permanent is
 * neither for as long as this collector is concerned — so their order within the batch is unobservable.
 *
 * Split out of [applicableStateBasedActions] when the third arrived and pushed that function past
 * detekt's complexity budget: split rather than suppressed, along the seam the CR already draws.
 *
 * Ordering note (docs/design/protection.md §2.2): these checks read *layered* protections, and layered
 * characteristics are computed on read, so an Aura granting protection is still contributing its grant
 * while the batch is collected.
 */
internal fun danglingAttachmentActions(state: GameState): List<StateBasedAction> =
    buildList {
        for (obj in state.sharedZones.battlefield) {
            val restriction = enchantRestrictionOf(state, obj)
            if (restriction != null && !auraAttachmentIsLegal(state, obj, restriction)) {
                add(StateBasedAction.AuraFallsOff(obj.id))
            }
        }
        for (obj in state.sharedZones.battlefield) {
            if (isEquipment(state, obj) && obj.attachedTo != null && !equipmentAttachmentIsLegal(state, obj)) {
                add(StateBasedAction.EquipmentUnattaches(obj.id))
            }
        }
        for (obj in state.sharedZones.battlefield) {
            if (obj.attachedTo != null && isBestowPermanent(state, obj) && !bestowAttachmentIsLegal(state, obj)) {
                add(StateBasedAction.BestowedPermanentUnattaches(obj.id))
            }
        }
    }

/**
 * Whether [aura]'s attachment is legal right now (CR 704.5m): it is attached to a battlefield object
 * that still satisfies the Aura's enchant [restriction]. Control is ownership in the MVP pool
 * (docs/design/layer-system.md §4), so "you control" reads the Aura's owner.
 */
private fun auraAttachmentIsLegal(
    state: GameState,
    aura: GameObject,
    restriction: EnchantRestriction,
): Boolean {
    val attachedTo = aura.attachedTo ?: return false
    val target = state.sharedZones.battlefield.firstOrNull { it.id == attachedTo }
    return target != null &&
        // CR 702.16c: "A permanent … with protection can't be enchanted by Auras that have the
        // stated quality. Such Auras attached to the permanent … with protection will be put into
        // their owners' graveyards as a state-based action." So a still-present enchanted object can
        // make the attachment illegal — which the comment at the call site said was unreachable
        // until protection existed, and is exactly the case that would otherwise rot silently
        // (docs/design/protection.md §2.2).
        !hasProtectionFrom(state, attachedTo, aura.card) &&
        satisfiesEnchantRestriction(state, restriction, target, aura.owner)
}
