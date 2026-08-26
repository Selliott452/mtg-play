package dev.mtgplay.core.definition

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/**
 * A **dungeon card** (CR 309) — a nontraditional card that never enters any ordinary zone, waits in the
 * command zone while a player is in it, and is a directed graph of *rooms* rather than a spell or a
 * permanent. Card-definition data, additive and flagged core (`W10-A`).
 *
 * **Why this is a type and not a [CardDefinition].** Every other definition in this package describes an
 * object that can be cast, played, or put onto the battlefield; a dungeon can do none of those things
 * (CR 309.2 — "a dungeon card can't be cast, and it can never be put onto the battlefield"). What it has
 * instead is a room graph, and a room is a **name plus a triggered ability** that fires when a player's
 * venture marker is put on it (CR 309.5). So the dungeon's whole content is [rooms], and the engine's
 * whole interaction with it is moving one marker.
 *
 * **The graph is data, and `mtg-rules` never names a dungeon.** ADR-003 puts nouns in core and verbs in
 * rules: this type is the noun, the Undercity is a value of it declared in `mtg-cards`, and the venture
 * keyword action (CR 701.49) is the verb in `mtg-rules`. The engine knows how to move a marker along
 * *any* graph of this shape and knows the name of none of them — the dungeon a "take the initiative"
 * effect ventures into is supplied by the card that prints the line and then carried in the game state
 * ([dev.mtgplay.core.state.InitiativeState.dungeon]).
 *
 * **Entry is always at [rooms]`[0]`** (CR 309.4: "…they put their venture marker on the first room").
 * Successors are indices *into this same list* rather than names, so a malformed graph is caught here
 * once rather than at every traversal, and the traversal itself is a list lookup.
 *
 * @property name the dungeon's printed name (CR 309.3), e.g. "Undercity".
 * @property rooms the dungeon's rooms in printed order; index 0 is the entrance.
 */
data class Dungeon(
    val name: String,
    val rooms: PersistentList<DungeonRoom>,
) {
    init {
        require(name.isNotBlank()) { "CR 309.3: a dungeon card has a name" }
        require(rooms.isNotEmpty()) { "CR 309.4: a dungeon has at least one room to enter" }
        val names = rooms.map(DungeonRoom::name)
        require(names.distinct().size == names.size) {
            "CR 309.3: a dungeon's room names are distinct, got $names"
        }
        rooms.forEachIndexed { index, room ->
            room.successors.forEach { successor ->
                require(successor in rooms.indices) {
                    "CR 309.4: ${room.name} leads to room index $successor, which $name does not have"
                }
                // CR 309.4: "the player moves their venture marker to the *next* room". A dungeon's
                // rooms are printed top-to-bottom and every printed successor is strictly later, which
                // is also what makes a venture terminate: a graph with a back-edge would let a player
                // loop forever and never complete the dungeon (CR 309.6).
                require(successor > index) {
                    "CR 309.4: ${room.name} leads backwards to ${rooms[successor].name}; a dungeon's " +
                        "rooms lead onward, or a venture would never reach the last room"
                }
            }
        }
        // CR 309.6: a dungeon is completed by reaching a room with nowhere left to go, so exactly one
        // room must be terminal — two would be two different endings and none would be no ending.
        val terminal = rooms.filter { it.successors.isEmpty() }
        require(terminal.size == 1) {
            "CR 309.6: a dungeon has exactly one last room, but $name has ${terminal.size}: " +
                terminal.map(DungeonRoom::name)
        }
        require(rooms.last().successors.isEmpty()) {
            "CR 309.6: a dungeon's last room is printed last, but $name's is ${terminal.first().name}"
        }
    }

    /**
     * The rooms whose printed ability this engine does not implement (see
     * [DungeonRoomAbility.Unimplemented]) — empty for a dungeon the engine can play end to end.
     *
     * Published so a card packet can *pin* the gap rather than discover it in a game: a dungeon with a
     * non-empty answer here must not be reachable from any registered card, because entering one of
     * these rooms fails loudly (`mtg-rules`) rather than quietly doing nothing.
     */
    val unimplementedRooms: List<DungeonRoom>
        get() = rooms.filter { it.ability is DungeonRoomAbility.Unimplemented }

    /**
     * Whether [room] is this dungeon's **last** room (CR 309.6) — the one a venture marker reaching it
     * completes the dungeon from, because it leads nowhere.
     *
     * "Has no successors" and "is the last room" are the same fact here, guaranteed by the `init` above:
     * exactly one room is terminal and it is printed last. Naming it makes the completion check read as
     * the rule rather than as a graph property.
     */
    fun isLastRoom(room: Int): Boolean = rooms[room].successors.isEmpty()
}

/**
 * One **room** of a [Dungeon] (CR 309.3): its printed name, the ability that triggers when a venture
 * marker is put on it (CR 309.5), and the rooms it leads to. Additive, flagged core (`W10-A`).
 *
 * **A room's ability is an ordinary triggered ability** (CR 309.5): it uses the stack, it can be
 * responded to, and it can target. That is why [ability] carries a whole [TriggeredAbility] rather than
 * a bare [ResolutionEffect] — a room that draws a card and a room that deals 5 damage to a target player
 * differ only in the ability, and the room needs the clause carrier, the target spec, and the
 * intervening-if slot exactly as any printed trigger does.
 *
 * **Branching is a choice, not a lottery** (CR 309.4): a room with two successors makes the venturing
 * player choose which one to enter, and that choice is an enumerated decision (ADR-005). A room with one
 * successor makes no choice, and a room with none is the dungeon's last (CR 309.6).
 *
 * @property name the room's printed name (CR 309.3), e.g. "Secret Entrance".
 * @property ability what happens when a venture marker is put on this room (CR 309.5).
 * @property successors the indices, in [Dungeon.rooms], of the rooms this one leads to, in printed
 *   order; empty for the dungeon's last room.
 */
data class DungeonRoom(
    val name: String,
    val ability: DungeonRoomAbility,
    val successors: PersistentList<Int> = persistentListOf(),
) {
    init {
        require(name.isNotBlank()) { "CR 309.3: a dungeon room has a name" }
        require(successors.distinct().size == successors.size) {
            "CR 309.4: $name leads to each room at most once, got $successors"
        }
    }
}

/**
 * What a [DungeonRoom] does when a venture marker is put on it (CR 309.5) — either the triggered ability
 * itself, or an honest record that this engine cannot express the printed line. Additive, flagged core
 * (`W10-A`).
 *
 * **The second member is not a placeholder for "nothing happens".** A room whose ability silently did
 * nothing would be exactly the plausible-looking wrong card CONVENTIONS.md and PLAN.md §7 forbid: a
 * training agent would learn that the room is free, and the dungeon would be a different card. So the
 * member carries the printed text it *cannot* run and the diagnosis of what the engine would need, and
 * `mtg-rules` refuses to enter such a room at all. A dungeon holding one is therefore encodable — the
 * graph is still worth recording faithfully, and a spec can pin exactly which rooms are missing — but
 * unreachable from any registered card until the gap is closed.
 */
sealed interface DungeonRoomAbility {
    /** The room's printed line, as a triggered ability the engine puts on the stack (CR 309.5). */
    data class Runs(
        val ability: TriggeredAbility,
    ) : DungeonRoomAbility

    /**
     * The room's printed line, which this engine cannot express (CONVENTIONS.md, "fail loudly; never
     * silently approximate").
     *
     * @property printed the room's oracle text, verbatim, so the gap is readable where it is declared.
     * @property diagnosis what the engine would need in order to run it — the precise blocker, not
     *   "unsupported".
     */
    data class Unimplemented(
        val printed: String,
        val diagnosis: String,
    ) : DungeonRoomAbility {
        init {
            require(printed.isNotBlank()) { "an unimplemented room records the printed line it cannot run" }
            require(diagnosis.isNotBlank()) { "an unimplemented room records why, precisely" }
        }
    }
}
