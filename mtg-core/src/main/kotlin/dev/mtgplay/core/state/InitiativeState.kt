package dev.mtgplay.core.state

import dev.mtgplay.core.definition.Dungeon
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import kotlinx.collections.immutable.PersistentMap

/**
 * **The initiative** (CR 701.51) and every player's position in the dungeon it ventures into (CR 309.4)
 * — the whole of the mechanic's game state, in one record. Additive, flagged core (`W10-A`).
 *
 * **There is no initiative in a game until an effect creates one**, which is why the whole record is
 * nullable on [GameState] rather than being a `PlayerId?` field beside the turn. Nothing in the rules
 * removes it afterwards: once a player has taken the initiative some player always has it, and it only
 * ever changes hands (CR 701.51c). A `null` here therefore means "no card has said the word yet", and a
 * non-null one means the designation exists for the rest of the game.
 *
 * **The dungeon travels with the designation.** CR 701.51a fixes what taking the initiative ventures
 * into — Undercity, and no other dungeon — so the graph is captured here the first time a card creates
 * the designation and read from the state ever after. That is what lets `mtg-rules` implement the whole
 * mechanic without naming a card (ADR-003): the card that prints "you take the initiative" supplies the
 * [Dungeon] value once, and the upkeep venture (CR 701.51b) and the combat-damage handover (CR 701.51c)
 * never need to know where it came from.
 *
 * **A venture marker is per player, not per initiative-holder**, and that distinction is the mechanic's
 * one piece of hidden depth. CR 309.4 puts the marker on a dungeon *that player* is in; losing the
 * initiative does not take you out of the Undercity, so a player who loses it and takes it back resumes
 * from the room they had reached rather than starting over. Keying [markers] by seat rather than storing
 * one "current room" beside [holder] is what makes that true — and a two-player game reaches it as soon
 * as the initiative changes hands twice, which is the normal course of an initiative game.
 *
 * @property holder the player who currently has the initiative (CR 701.51a) — the *initiative holder*.
 * @property dungeon the dungeon a "take the initiative" effect ventures into (CR 701.51a); the Undercity
 *   for every card that prints the line.
 * @property markers each player's venture marker (CR 309.4), keyed by seat. A seat **absent** from the
 *   map is not in a dungeon: either it has never ventured, or it completed the dungeon and the card was
 *   removed (CR 309.6).
 */
data class InitiativeState(
    val holder: PlayerId,
    val dungeon: Dungeon,
    val markers: PersistentMap<PlayerId, VentureMarker>,
) {
    init {
        markers.forEach { (seat, marker) ->
            require(marker.room in dungeon.rooms.indices) {
                "CR 309.4: $seat's venture marker is on room ${marker.room}, which ${dungeon.name} does not have"
            }
        }
    }

    /** The room [seat]'s venture marker is on (CR 309.4), or `null` when that seat is not in a dungeon. */
    fun roomOf(seat: PlayerId): Int? = markers[seat]?.room
}

/**
 * One player's **venture marker** (CR 309.4): which room of the dungeon they are in, and the identity of
 * the dungeon card that is in their command zone while they are in it (CR 309.2). Additive, flagged core
 * (`W10-A`).
 *
 * **The object id is not decoration.** A room's ability is a triggered ability whose *source* is the
 * dungeon card (CR 309.5), and every fired trigger records its source's id as last-known information
 * (CR 603.10, [PendingTrigger.sourceId]). Each player who ventures gets their own dungeon card in their
 * own command zone, so the id is per marker rather than one for the whole game — two players in the
 * Undercity at once are in two dungeons, not one shared board.
 *
 * @property room the index, in [Dungeon.rooms], of the room the marker sits on.
 * @property dungeonObjectId the id of this player's dungeon card in the command zone (CR 309.2, CR 400.7).
 */
data class VentureMarker(
    val room: Int,
    val dungeonObjectId: ObjectId,
) {
    init {
        require(room >= 0) { "CR 309.4: a venture marker names a room index, was $room" }
    }
}

/**
 * A **venture that has paused for its branch choice** (CR 309.4): the venturing player's marker is still
 * on [fromRoom], and they must name which of that room's successors to move it to. Additive, flagged
 * core (`W10-A`).
 *
 * **The only pause the dungeon has**, and it exists because a room may lead to two. CR 309.4's "the
 * player moves their marker to the next room" is unambiguous for a room with one successor and a genuine
 * choice for a room with two, so the branch is an enumerated decision (ADR-005) rather than anything the
 * engine picks. It is opened by the venture ability *as it resolves* — the ability is still on the stack
 * while this record is open, exactly like every other mid-resolution clause pause.
 *
 * **A marker is never mid-move.** [fromRoom] is where the marker still is, not a half-applied
 * destination: a game paused here and resumed from its state alone (ADR-004) re-derives the same option
 * list from the same room. The move happens in one transition when the answer arrives, together with the
 * new room's CR 309.5 trigger.
 *
 * @property player the venturing player, who chooses (CR 309.4).
 * @property fromRoom the index, in [InitiativeState.dungeon]'s rooms, of the room the marker is still on.
 */
data class PendingVenture(
    val player: PlayerId,
    val fromRoom: Int,
) {
    init {
        require(fromRoom >= 0) { "CR 309.4: a pending venture names the room its marker is on, was $fromRoom" }
    }
}
