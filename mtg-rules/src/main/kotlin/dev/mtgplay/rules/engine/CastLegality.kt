package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.AdditionalCost
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.SacrificeRequirement
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState

/*
 * Cast legality shared by enumeration (ADR-005) and the madness reflexive-cast viability check. Every
 * gate here excludes a cast that would dead-end mid-pipeline, so a surfaced cast always completes.
 */

/**
 * Whether every target [definition] requires is available and [cost] can be paid (CR 601.2c, g). [self]
 * is the card that would be cast, excluded from its own target enumeration.
 */
internal fun targetsAndCostAvailable(
    state: GameState,
    seat: PlayerId,
    definition: SpellDefinition,
    cost: ManaCost,
    self: ObjectId?,
): Boolean =
    targetsAvailable(state, definition.targetSpec, seat, self) &&
        enumeratePaymentPlans(state, seat, cost).isNotEmpty()

/**
 * Whether [seat] may cast the card [sourceObject] via [permission] from a priority window (CR 117.1a):
 * the card's timing permits it, the additional "exile N others" cost is satisfiable, and its targets
 * and alternative cost are available. Used to enumerate flashback and escape casts (ADR-005).
 */
internal fun permissionCastIsLegal(
    state: GameState,
    seat: PlayerId,
    definition: SpellDefinition,
    permission: CastingPermission,
    sourceObject: GameObject,
): Boolean =
    timingPermitsCast(state, seat, definition.timing) &&
        additionalExileSatisfiable(state, seat, permission, sourceObject) &&
        sacrificeSatisfiable(state, seat, permission.sacrifice) &&
        additionalDiscardSatisfiable(state, seat, definition, sourceObject.id, permission.source) &&
        plotMarkerAllows(state, permission, sourceObject) &&
        targetsAndCostAvailable(state, seat, definition, permission.cost, self = sourceObject.id)

/**
 * Whether a [CastingPermission.Plot] free cast is allowed for [sourceObject] right now (CR 702.140): the
 * exile card must have been plotted and not this turn ([plotFreeCastLegal]). Trivially true for every
 * other permission — only plot gates on the plotted-turn marker.
 */
private fun plotMarkerAllows(
    state: GameState,
    permission: CastingPermission,
    sourceObject: GameObject,
): Boolean = permission !is CastingPermission.Plot || plotFreeCastLegal(state, sourceObject)

/**
 * Whether a card's intrinsic additional discard cost (Grab the Prize's "discard a card", CR 601.2b)
 * can be paid: the caster has at least the required count of hand cards other than the one being cast.
 * The card being cast is excluded only when it is cast from the hand (source [CastSource.HAND]) — a
 * permission cast draws the card from elsewhere, so the whole hand is discardable. Trivially true when
 * the definition has no additional discard cost.
 */
internal fun additionalDiscardSatisfiable(
    state: GameState,
    seat: PlayerId,
    definition: SpellDefinition,
    castObjectId: ObjectId,
    source: CastSource,
): Boolean {
    val cost = definition.additionalCost
    if (cost !is AdditionalCost.DiscardCards) return true
    val available = state.player(seat).hand.count { !(source == CastSource.HAND && it.id == castObjectId) }
    return available >= cost.count
}

/**
 * The battlefield permanents [seat] controls that satisfy [requirement] (CR 601.2h): its own
 * permanents whose printed subtypes include the requirement's subtype (Mountain), in battlefield
 * order. Control is ownership in the MVP pool.
 */
internal fun sacrificeableFor(
    state: GameState,
    seat: PlayerId,
    requirement: SacrificeRequirement,
): List<GameObject> =
    state.sharedZones.battlefield.filter { obj ->
        obj.owner == seat &&
            state.definitions[obj.card]
                ?.characteristics
                ?.subtypes
                ?.contains(requirement.subtype) == true
    }

/**
 * Whether a non-mana [requirement] sacrifice cost can be paid: [seat] controls at least the required
 * count of matching permanents. Trivially true when the permission has no sacrifice cost (`null`).
 */
internal fun sacrificeSatisfiable(
    state: GameState,
    seat: PlayerId,
    requirement: SacrificeRequirement?,
): Boolean = requirement == null || sacrificeableFor(state, seat, requirement).size >= requirement.count

/**
 * Whether [permission]'s additional "exile N other cards" cost (CR 702.139a) can be paid: the source
 * zone holds at least that many cards *other* than [sourceObject]. Trivially true when the permission
 * has no such cost.
 */
internal fun additionalExileSatisfiable(
    state: GameState,
    seat: PlayerId,
    permission: CastingPermission,
    sourceObject: GameObject,
): Boolean {
    val needed = permission.additionalExileCount
    if (needed == 0) return true
    val others = objectsInZone(state, seat, permission.source).count { it.id != sourceObject.id }
    return others >= needed
}

/**
 * Whether a madness cast of [permission] is currently possible for [owner] (CR 702.35b): its target
 * and its madness cost are available. Timing is deliberately not checked — a madness card is cast as
 * the reflexive trigger resolves, not from a priority window, so its normal timing does not restrict it
 * (CR 702.35b) — and madness carries no additional cost.
 */
internal fun madnessCastViable(
    state: GameState,
    owner: PlayerId,
    definition: SpellDefinition,
    permission: CastingPermission,
    exiledObjectId: ObjectId,
): Boolean = targetsAndCostAvailable(state, owner, definition, permission.cost, self = exiledObjectId)
