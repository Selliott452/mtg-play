package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState

/*
 * The cast-from-elsewhere zone seam (docs/decklists.md): the casting pipeline is generalized over the
 * [CastSource] a cast draws from — the caster's hand (the normal cast), their graveyard (flashback,
 * escape), or exile (madness, plot). These read-and-move helpers are the *only* place the source zone
 * is resolved to a concrete zone; the propose stage (CR 601.2a) and the additional-cost payment
 * (CR 601.2h) call them and nothing else downstream knows where the card came from.
 */

/** The object [objectId] in [caster]'s [source] zone (CR 601.2a), or `null` if it is not there. */
internal fun objectInZone(
    state: GameState,
    caster: PlayerId,
    source: CastSource,
    objectId: ObjectId,
): GameObject? = objectsInZone(state, caster, source).firstOrNull { it.id == objectId }

/** Every object in [caster]'s [source] zone, in zone order (CR 601.2a). */
internal fun objectsInZone(
    state: GameState,
    caster: PlayerId,
    source: CastSource,
): List<GameObject> =
    when (source) {
        CastSource.HAND -> state.player(caster).hand
        CastSource.GRAVEYARD -> state.player(caster).graveyard
        // Exile is shared; a cast draws only the caster's own cards from it (CR 406, CR 601.2a).
        CastSource.EXILE -> state.sharedZones.exile.filter { it.owner == caster }
    }

/**
 * Removes the object [objectId] from [caster]'s [source] zone (CR 400.7 — the object is about to be
 * reborn elsewhere). Fails loudly if it is not there; the caller has already located it.
 */
internal fun removeFromZone(
    state: GameState,
    caster: PlayerId,
    source: CastSource,
    objectId: ObjectId,
): GameState =
    when (source) {
        CastSource.HAND ->
            state.updatePlayer(caster) { it.copy(hand = it.hand.removingAt(indexIn(it.hand, objectId, "hand"))) }
        CastSource.GRAVEYARD ->
            state.updatePlayer(caster) {
                it.copy(graveyard = it.graveyard.removingAt(indexIn(it.graveyard, objectId, "graveyard")))
            }
        CastSource.EXILE ->
            state.updateExile { exile -> exile.removingAt(indexIn(exile, objectId, "exile")) }
    }

private fun indexIn(
    zone: List<GameObject>,
    objectId: ObjectId,
    label: String,
): Int {
    val index = zone.indexOfFirst { it.id == objectId }
    require(index >= 0) { "object $objectId is not in the $label zone" }
    return index
}

/**
 * The single madness casting permission of [card]'s definition (CR 702.35), or `null` if the card has
 * none. A card carries at most one madness permission; two would be an ill-formed definition.
 */
internal fun madnessPermissionOf(
    state: GameState,
    card: CardRef,
): CastingPermission.Madness? =
    (state.definitions[card] as? SpellDefinition)
        ?.castingPermissions
        ?.filterIsInstance<CastingPermission.Madness>()
        ?.singleOrNull()
