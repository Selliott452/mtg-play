package dev.mtgplay.acceptance.invariant

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState

/*
 * What *kind* of permanent an object is, for [Invariant.ATTACHMENT_INTEGRITY] — the three questions
 * that check asks and nothing else.
 *
 * Split out of `InvariantChecker.kt` when `FW-EQUIP` gave attachment a second kind of carrier and
 * pushed that file past detekt's per-file function budget. The split is along a real seam: the checker
 * owns *which* properties must hold, and this file owns the classification those properties are stated
 * over.
 *
 * **Every answer here is read from printed characteristics, never through the layer engine**, and that
 * is the oracle discipline rather than an oversight. An invariant checker that asked the same code the
 * engine used to decide would agree with the engine by construction and prove nothing. No effect in the
 * gauntlet pool grants the Equipment subtype or the enchant ability, and the one effect that grants the
 * creature *type* — Kenku Artificer, `FW-TYPECHANGE` — makes the printed read **stricter** here rather
 * than wrong: an Equipment attached to an animated artifact would be flagged, which is the direction an
 * oracle should err in, and the day a deck can reach that board this file gains the layered read with a
 * test that pins the difference.
 */

/**
 * Whether [obj] is an Equipment (CR 301.5a): an artifact with the Equipment subtype — the only thing
 * that marks one.
 */
internal fun isEquipmentPermanent(
    state: GameState,
    obj: GameObject,
): Boolean {
    val printed = state.definitions[obj.card]?.characteristics ?: return false
    return CardType.ARTIFACT in printed.cardTypes && EQUIPMENT in printed.subtypes
}

/** The artifact subtype that makes an artifact an Equipment (CR 301.5a). */
private val EQUIPMENT: Subtype = Subtype("Equipment")

/**
 * Whether the battlefield object [id] is a creature (CR 302.1) by its printed type line — what
 * CR 301.5b requires of an Equipment's host. `false` for an object that is not on the battlefield,
 * which the caller has already reported as a dangling attachment.
 */
internal fun isPrintedCreature(
    state: GameState,
    id: ObjectId,
): Boolean {
    val obj = state.sharedZones.battlefield.firstOrNull { it.id == id }
    return CardType.CREATURE in (state.definitions[obj?.card]?.characteristics?.cardTypes ?: emptySet())
}

/** Whether [obj] is an Aura: a permanent whose enchant ability is a [TargetSpec.Enchantable]. */
internal fun isAura(
    state: GameState,
    obj: GameObject,
): Boolean {
    val definition = state.definitions[obj.card] as? SpellDefinition ?: return false
    return definition.targetSpec is TargetSpec.Enchantable
}
