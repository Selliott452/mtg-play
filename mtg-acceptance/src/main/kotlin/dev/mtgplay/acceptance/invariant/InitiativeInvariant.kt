package dev.mtgplay.acceptance.invariant

import dev.mtgplay.core.state.GameState

/**
 * [Invariant.DUNGEON_OBJECT_IDENTITY]: every venture marker's dungeon card (CR 309.2) is a distinct
 * object, allocated from the same counter as everything else (CR 400.7) and shared with nothing in any
 * ordinary zone. Added with `W10-A`.
 *
 * **This is the one initiative invariant `GameState` cannot check for itself**, which is why it is
 * here and why the rest of the mechanic's well-formedness is not. `GameState`'s construction already
 * refuses an unseated holder, a marker on a room the dungeon does not have, and a pending venture whose
 * marker has moved — but its id-uniqueness check walks `allObjects()`, and a dungeon card lives in a
 * **command zone** this engine models as nothing but a marker. So it is the one place an id can be
 * minted, duplicated, or collided with a real object's without anything noticing.
 *
 * The failure it backstops is exactly the quiet kind: a duplicated id makes two players' room triggers
 * name one source, so a CR 603.10 last-known-information read — or anything that later grows to look a
 * trigger's source up — silently answers for the wrong dungeon. Nothing throws; the game simply plays a
 * different card.
 *
 * A game with no initiative has no markers and so no violations, which is every game the current
 * registry can produce.
 */
internal fun checkDungeonObjectIdentity(
    state: GameState,
    residences: List<ZoneResidence>,
): List<Violation> {
    val initiative = state.initiative ?: return emptyList()
    val zoneIds = residences.map { it.obj.id }.toSet()
    return buildList {
        val markers = initiative.markers.entries.toList()
        markers.forEach { (seat, marker) ->
            val id = marker.dungeonObjectId
            if (id.value >= state.nextObjectId) {
                add(
                    Violation(
                        Invariant.DUNGEON_OBJECT_IDENTITY,
                        "CR 400.7: $seat's ${initiative.dungeon.name} card has id ${id.value}, which is not " +
                            "below the allocation counter ${state.nextObjectId}",
                    ),
                )
            }
            if (id in zoneIds) {
                add(
                    Violation(
                        Invariant.DUNGEON_OBJECT_IDENTITY,
                        "CR 400.7: $seat's ${initiative.dungeon.name} card shares id ${id.value} with an " +
                            "object in an ordinary zone",
                    ),
                )
            }
        }
        val ids = markers.map { it.value.dungeonObjectId }
        if (ids.distinct().size != ids.size) {
            add(
                Violation(
                    Invariant.DUNGEON_OBJECT_IDENTITY,
                    "CR 309.2: each player in a dungeon has their own dungeon card, but two markers share " +
                        "an id: $ids",
                ),
            )
        }
    }
}
