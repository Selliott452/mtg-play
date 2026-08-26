package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState

/*
 * The CR 704.5n half of the attachment state-based actions: what an Equipment is, and when its
 * attachment has stopped being legal. Added by `FW-EQUIP`, in its own file because `StateBasedActions.kt`
 * owns the *spine* — which actions exist, how a batch is sorted, and the CR 704.3 repeat loop — and this
 * is one action's condition.
 *
 * **Its sibling, CR 704.5m, does the opposite thing on the same condition**, which is the single most
 * important fact about Equipment and the reason the two are not one shared "dangling attachment" check:
 * an Aura attached to an illegal permanent is put into its owner's graveyard, and an Equipment simply
 * lets go and stays on the battlefield.
 */

/** The artifact subtype that makes an artifact an Equipment (CR 301.5a). */
private val EQUIPMENT: Subtype = Subtype("Equipment")

/**
 * Whether the battlefield object [obj] is an Equipment (CR 301.5a): an artifact with the Equipment
 * artifact subtype, read through the layered type line and the one battlefield subtype seam.
 *
 * The subtype *is* the definition — CR 301.5a says "Equipment is a subtype of artifact" and nothing else
 * marks one — so this needs no field on [dev.mtgplay.core.definition.CardDefinition]. Neither does what
 * an Equipment may be attached to: CR 301.5b gives every Equipment ever printed the same host
 * requirement, a creature, so there is no per-card restriction to carry either. That is the whole
 * structural difference from an Aura, whose enchant ability states its own.
 */
internal fun isEquipment(
    state: GameState,
    obj: GameObject,
): Boolean = CardType.ARTIFACT in effectiveCardTypes(state, obj.id) && hasSubtype(state, obj.id, EQUIPMENT)

/**
 * Whether [equipment]'s attachment is legal right now (CR 301.5b, CR 704.5n): it is attached to a
 * battlefield object that is **a creature**.
 *
 * Narrower than the Aura check in one way and wider in another, and both are the rules rather than a
 * simplification. Narrower: there is no per-card restriction to consult, per [isEquipment]. Wider: it
 * does **not** re-check "you control", even though equip's own target is "creature you control"
 * (CR 702.6b) — CR 301.5b's continuing legality is about creature-hood alone, and an Equipment stays
 * attached to a creature whose control changed. Control changing is a CR 613 layer-2 effect this pool
 * does not have, so the two readings cannot yet disagree; stating the right one now means that the day
 * they can, this line does not have to be found again.
 *
 * Creature-hood is the **layered** read (CR 613 layer 4), so an Equipment attached to a permanent that a
 * type-changing effect animated stays attached, and one whose host stops being a creature lets go.
 */
internal fun equipmentAttachmentIsLegal(
    state: GameState,
    equipment: GameObject,
): Boolean {
    val attachedTo = equipment.attachedTo
    val host = state.sharedZones.battlefield.firstOrNull { it.id == attachedTo }
    return host != null && isCreature(state, host)
}
