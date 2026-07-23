package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState

/*
 * Cast legality shared by enumeration (ADR-005) and the madness reflexive-cast viability check. Every
 * gate here excludes a cast that would dead-end mid-pipeline, so a surfaced cast always completes.
 */

/** Whether every target [definition] requires is available and [cost] can be paid (CR 601.2c, g). */
internal fun targetsAndCostAvailable(
    state: GameState,
    seat: PlayerId,
    definition: SpellDefinition,
    cost: ManaCost,
): Boolean =
    targetsAvailable(state, definition.targetSpec, seat) &&
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
        targetsAndCostAvailable(state, seat, definition, permission.cost)

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
): Boolean = targetsAndCostAvailable(state, owner, definition, permission.cost)
