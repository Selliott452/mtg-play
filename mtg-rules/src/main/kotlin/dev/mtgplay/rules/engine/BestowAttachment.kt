package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState

/*
 * Bestow's battlefield half (CR 702.103) — what a bestowed permanent is while it is attached, and what
 * happens when the thing it is attached to stops being a legal host. Additive (`W10-C`).
 *
 * Its own file, beside `EquipmentAttachment.kt`, because the three attachment state-based actions are
 * three different rules with three different outcomes on the *same* condition, and the whole value of
 * keeping them apart is that each one's outcome is stated where its condition is:
 *
 * - **CR 704.5m** — an Aura attached to an illegal object is put into its owner's graveyard.
 * - **CR 704.5n** — an Equipment attached to an illegal permanent becomes unattached and stays.
 * - **CR 702.103c** — a permanent with bestow attached to an illegal object becomes unattached and
 *   stays, *and then stops being an Aura*, because its type-changing static ability's condition
 *   ("attached to a creature") no longer holds.
 *
 * The third is not the second with a different word. An Equipment that lets go is still an Equipment,
 * waiting to be equipped again; a bestowed permanent that lets go **becomes a creature** and never
 * returns — nothing re-attaches it. That transformation is the reason the card is played, and it is not
 * written here at all: it falls out of [dev.mtgplay.core.definition.StaticCondition.AttachedToCreature]
 * being re-evaluated on every characteristic read (docs/design/layer-system.md §5). This file only has
 * to make sure the *graveyard* rule never gets to it first.
 */

/**
 * The bestow ability of [definition] (CR 702.103a), or `null` for a card without one.
 *
 * A card carries at most one; two would be an ill-formed definition and fail loudly here rather than
 * silently picking the first, in the shape [prototypeOf] set for prototype.
 */
internal fun bestowOf(definition: CardDefinition?): CastingPermission.Bestow? =
    (definition as? SpellDefinition)
        ?.castingPermissions
        ?.filterIsInstance<CastingPermission.Bestow>()
        ?.let { permissions ->
            require(permissions.size <= 1) {
                "CR 702.103a: a card has at most one bestow ability, but " +
                    "${definition.characteristics.name} declares ${permissions.size}"
            }
            permissions.firstOrNull()
        }

/**
 * Whether the battlefield object [obj] is a permanent with bestow (CR 702.103) — a card printing the
 * keyword, whether or not it was cast for its bestow cost.
 *
 * **Printed, not "was it bestowed", and the difference is nothing.** A permanent with bestow that was
 * cast normally is a creature and is attached to nothing, so every check below that reads this one is
 * gated on an attachment it can never have. Recording a "was bestowed" flag on the object would be a
 * second source of truth for a question the attachment already answers, and CR 702.103c is written about
 * *a permanent with bestow* for exactly that reason.
 */
internal fun isBestowPermanent(
    state: GameState,
    obj: GameObject,
): Boolean = bestowOf(state.definitions[obj.card]) != null

/**
 * Whether [permanent]'s bestow attachment is legal right now (CR 702.103c, CR 303.4a): it is attached to
 * a battlefield permanent that is a **creature** and that does not have protection from it.
 *
 * The three clauses are the three ways the printed line can stop being satisfiable, and each is a real
 * play rather than a corner:
 *
 * - the enchanted creature **left the battlefield** (it died, was bounced, was exiled) — the common case,
 *   and the one that makes bestow a two-for-nothing instead of a two-for-one;
 * - the enchanted permanent **stopped being a creature** (CR 613 layer 4), which is why creature-hood is
 *   the layered read rather than the printed one;
 * - the enchanted creature **gained protection** from the bestowed permanent's quality (CR 702.16c).
 *
 * All three produce the same outcome here and it is **not** the Aura outcome: the permanent becomes
 * unattached and stays on the battlefield (CR 702.103c). It is then a creature again, because its
 * type-changing static ability's condition has stopped holding — which happens in the same instant, with
 * nothing on the stack, and is not this function's business.
 */
internal fun bestowAttachmentIsLegal(
    state: GameState,
    permanent: GameObject,
): Boolean {
    val attachedTo = permanent.attachedTo
    val host = state.sharedZones.battlefield.firstOrNull { it.id == attachedTo }
    return host != null &&
        isCreature(state, host) &&
        // CR 702.16c: a permanent with protection from the Aura's quality can't be enchanted by it.
        !hasProtectionFrom(state, host.id, permanent.card)
}
