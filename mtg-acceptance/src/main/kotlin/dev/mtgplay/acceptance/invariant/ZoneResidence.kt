package dev.mtgplay.acceptance.invariant

import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.zone.ZoneId

/**
 * One object's residence: the [GameObject] and the [zone] it currently sits in.
 *
 * Enumerating a state as a flat list of residences is the shared substrate for two things that
 * both need "every object, once, with where it lives": the zone-conservation check (an id that
 * appears in more than one residence is in more than one zone) and the state fingerprint (which
 * digests object ids and cards per zone). Extraction is separated from validation so each
 * invariant check is a pure function of a residence list and thus independently testable, even
 * for corruption that a [GameState] cannot itself represent.
 *
 * @property zone which zone the object lives in.
 * @property obj the object residing there.
 */
data class ZoneResidence(
    val zone: ZoneId,
    val obj: GameObject,
) {
    companion object {
        /**
         * Flattens [state] into its residences in a canonical, deterministic order: each seat in
         * ascending seat order (library top-first, then hand, then graveyard), then the shared
         * battlefield, stack, and exile. The order is stable across equal states, which is what
         * makes both the derived fingerprint and the conservation checks reproducible.
         */
        fun of(state: GameState): List<ZoneResidence> =
            buildList {
                state.players.entries
                    .sortedBy { it.key.seat }
                    .forEach { (seat, player) ->
                        player.library.forEach { add(ZoneResidence(ZoneId.Library(seat), it)) }
                        player.hand.forEach { add(ZoneResidence(ZoneId.Hand(seat), it)) }
                        player.graveyard.forEach { add(ZoneResidence(ZoneId.Graveyard(seat), it)) }
                    }
                state.sharedZones.battlefield.forEach { add(ZoneResidence(ZoneId.Battlefield, it)) }
                // The stack holds typed entries (P2.1); the residing object is the entry's card
                // object — a spell on the stack is as much a card as one in a hand (CR 405.2).
                state.sharedZones.stack.forEach { add(ZoneResidence(ZoneId.Stack, it.obj)) }
                state.sharedZones.exile.forEach { add(ZoneResidence(ZoneId.Exile, it)) }
            }
    }
}
