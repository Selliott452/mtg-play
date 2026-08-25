package dev.mtgplay.rules.engine

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.CombatState
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet

/*
 * The consequence of a creature-death state-based action (CR 704.5f/g, detected in
 * StateBasedActions.kt): the dying creatures leave the battlefield for their owners' graveyards,
 * and combat lets go of any that were fighting. Death detection is a pure read of the state; this
 * file performs the resulting moves. The two death causes live in [CreatureDeathCause].
 */

/**
 * Performs a batch of creature-death state-based actions simultaneously (CR 704.3): every dying
 * creature (already identified from the pre-batch state, so the batch is simultaneous — a creature
 * with lethal damage still dies even if what dealt that damage dies in the same batch) leaves the
 * battlefield for its owner's graveyard as a **new** object (CR 400.7), and combat then releases
 * any dead combatants ([clearCombatReferences]).
 *
 * The graveyard object is fresh: no marked damage, untapped — a reborn object carries no
 * battlefield-status memory (CR 400.7). Each move emits [GameEvent.CreatureDied].
 */
internal fun performCreatureDeaths(
    state: GameState,
    deaths: List<ObjectId>,
): GameState {
    val moved = deaths.fold(state, ::moveDeadCreatureToGraveyard)
    return clearCombatReferences(moved, deaths.toSet())
}

// Moves the dead battlefield creature [objectId] to its owner's graveyard as a new object
// (CR 704.5f/g put it there; CR 400.7 makes it a new object). Fails loudly if it is not on the
// battlefield — a death SBA acts only on battlefield creatures.
private fun moveDeadCreatureToGraveyard(
    state: GameState,
    objectId: ObjectId,
): GameState {
    val battlefield = state.sharedZones.battlefield
    val index = battlefield.indexOfFirst { it.id == objectId }
    require(index >= 0) { "CR 704.5: a dying creature must be on the battlefield, but $objectId is not" }
    val dead = battlefield[index]
    val (graveyardId, allocated) = state.allocateObjectId()
    val reborn = GameObject(id = graveyardId, card = dead.card, owner = dead.owner)
    val moved =
        allocated
            .updateBattlefield { it.removingAt(index) }
            .updatePlayer(dead.owner) { it.copy(graveyard = it.graveyard.adding(reborn)) }
            .emit(GameEvent.CreatureDied(objectId, dead.card, graveyardId))
    // CR 603.6b, CR 603.10: a "put into a graveyard from the battlefield" trigger fires now, matched
    // against the creature's pre-death state (no MVP creature has one, but a token or later card may).
    return announceBattlefieldDeparture(moved, dead, graveyardId)
}

/**
 * Releases the [dead] objects from the current combat (CR 506.4: a permanent is removed from
 * combat when it leaves the battlefield). A dead attacker drops out of the attacker list, and any
 * creature that was blocking it stops blocking (its block named a now-undeclared attacker); a dead
 * blocker drops out of the block list. Damage-assignment orders (CR 509.2) shrink to their
 * surviving blockers, and an order that falls below two blockers is discarded — a single- or
 * un-blocked attacker needs none. The rebuilt [CombatState] therefore satisfies its own
 * construction invariants, and the acceptance checker's [Invariant.COMBAT_REFERENCES_VALID] holds:
 * combat never references an object that has left the battlefield.
 *
 * A no-op when no combat is in progress (a creature Bolted in a main phase) or when no dead object
 * was in combat. Also reused when a permanent leaves the battlefield by sacrifice (CR 506.4).
 */
internal fun clearCombatReferences(
    state: GameState,
    dead: Set<ObjectId>,
): GameState {
    val combat = state.turn.combat
    if (combat == null || dead.none { referencesObject(combat, it) }) return state

    val attackers = combat.attackers.filter { it.attacker !in dead }.toPersistentList()
    val survivingAttackers = attackers.map { it.attacker }.toSet()
    val blocks =
        combat.blocks?.filter { it.blocker !in dead && it.attacker in survivingAttackers }?.toPersistentList()
    val blockerOrder =
        combat.blockerOrder.entries
            .filter { (attacker, _) -> attacker in survivingAttackers }
            .mapNotNull { (attacker, order) ->
                // The surviving order is still a permutation of this attacker's surviving blocks
                // (it was a permutation of its blocks, and both shrink by exactly the dead ones);
                // CR 509.2 keeps an order only while two or more blockers remain.
                val survivors = order.filter { it !in dead }.toPersistentList()
                if (survivors.size >= MINIMUM_ORDERED_BLOCKERS) attacker to survivors else null
            }.toMap()
            .toPersistentMap()
    // CR 509.1h: a blocked attacker stays blocked when its blockers die — only a dead *attacker*
    // drops its blocked status and any recorded trample assignment (both keyed by attacker).
    val blockedAttackers = combat.blockedAttackers.filter { it in survivingAttackers }.toPersistentSet()
    val trampleAssignments =
        combat.trampleAssignments.entries
            .filter { (attacker, _) -> attacker in survivingAttackers }
            .associate { (attacker, amount) -> attacker to amount }
            .toPersistentMap()

    return state.updateCombat {
        it.copy(
            attackers = attackers,
            blocks = blocks,
            blockedAttackers = blockedAttackers,
            blockerOrder = blockerOrder,
            trampleAssignments = trampleAssignments,
        )
    }
}

// Whether the combat state names [id] as an attacker or a blocker (CR 508–509).
private fun referencesObject(
    combat: CombatState,
    id: ObjectId,
): Boolean =
    combat.attackers.any { it.attacker == id } ||
        combat.blocks.orEmpty().any { it.blocker == id }

// CR 509.2: only an attacker blocked by two or more creatures keeps a damage-assignment order.
private const val MINIMUM_ORDERED_BLOCKERS: Int = 2
