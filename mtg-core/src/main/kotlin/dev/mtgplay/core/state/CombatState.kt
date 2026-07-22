package dev.mtgplay.core.state

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf

/**
 * One attacker's declaration (CR 508.1): the attacking creature and the player it is attacking.
 *
 * Two-player games have a single defending player, but the target is recorded per attacker
 * rather than once for the whole combat so the shape does not have to change when a creature can
 * attack a planeswalker or a second opponent (neither in the MVP pool).
 *
 * @property attacker the battlefield creature declared as an attacker (CR 508.1a).
 * @property defendingPlayer the player this creature is attacking (CR 508.1, CR 508.4).
 */
data class AttackerAssignment(
    val attacker: ObjectId,
    val defendingPlayer: PlayerId,
)

/**
 * One block (CR 509.1): a blocking creature and the attacker it blocks. A creature blocks at
 * most one attacker in the MVP pool (multi-block is not exercised), enforced by [CombatState].
 *
 * @property blocker the battlefield creature declared as a blocker (CR 509.1a).
 * @property attacker the attacker [blocker] is blocking; always one of the declared attackers.
 */
data class BlockAssignment(
    val blocker: ObjectId,
    val attacker: ObjectId,
)

/**
 * The combat progress of the current combat phase (CR 506–511): who is attacking whom, who is
 * blocking whom, the attacking player's chosen damage-assignment order for multi-blocked
 * attackers (CR 509.2), and how far the combat-damage step(s) have progressed.
 *
 * **Additive, flagged core (P3.1).** Carried on [Turn] as a nullable field: `null` outside the
 * combat phase and until the declare-attackers turn-based action engages combat (CR 508.1);
 * non-null once attackers have been declared, through the end of combat, when it is cleared
 * (CR 511.3). Construction validates internal consistency only — the ids reference *some*
 * creatures, no duplicate attackers or blockers, blocks name declared attackers, and orders are
 * permutations of the blocks — because a core noun cannot see the battlefield; that the ids are
 * battlefield creatures is checked where combat is declared (`mtg-rules`) and by the acceptance
 * invariant checker.
 *
 * @property attackers the declared attackers in declaration order (CR 508.1); possibly empty
 *   (the active player may declare no attackers — CR 508.8 then skips the later combat steps).
 * @property blocks the declared blocks, or `null` while the declare-blockers turn-based action
 *   is still pending (CR 509.1). A non-null empty list means blockers were declared and none
 *   were chosen — distinct from `null`, which means the choice has not happened yet.
 * @property blockerOrder the attacking player's damage-assignment order per attacker (CR 509.2),
 *   present only for an attacker blocked by two or more creatures; single-blocked and unblocked
 *   attackers need no explicit order. Each value is a permutation of that attacker's blockers.
 * @property firstStrikeDamageDealt whether the first combat-damage step has been performed — the
 *   step in which first-strike (and, in later packets, double-strike) creatures deal damage
 *   (CR 510.5). `false` when no such step is needed.
 * @property regularDamageDealt whether the regular combat-damage step has been performed — the
 *   step in which creatures without first strike deal damage (CR 510.5). Once `true`, combat
 *   damage is complete and the end-of-combat step follows.
 */
data class CombatState(
    val attackers: PersistentList<AttackerAssignment>,
    val blocks: PersistentList<BlockAssignment>? = null,
    val blockerOrder: PersistentMap<ObjectId, PersistentList<ObjectId>> = persistentMapOf(),
    val firstStrikeDamageDealt: Boolean = false,
    val regularDamageDealt: Boolean = false,
) {
    init {
        val attackerIds = attackers.map(AttackerAssignment::attacker)
        require(attackerIds.size == attackerIds.toSet().size) {
            "CR 508.1: each attacker is declared at most once, got $attackerIds"
        }
        val declared = attackerIds.toSet()
        val currentBlocks = blocks
        if (currentBlocks != null) {
            val blockerIds = currentBlocks.map(BlockAssignment::blocker)
            require(blockerIds.size == blockerIds.toSet().size) {
                "CR 509.1a: each creature blocks at most once, got $blockerIds"
            }
            currentBlocks.forEach { block ->
                require(block.attacker in declared) {
                    "CR 509.1: block by ${block.blocker} names undeclared attacker ${block.attacker}"
                }
            }
        }
        require(blockerOrder.isEmpty() || currentBlocks != null) {
            "CR 509.2: a blocker order requires blockers to have been declared"
        }
        blockerOrder.forEach { (attacker, order) ->
            require(attacker in declared) {
                "CR 509.2: blocker order names undeclared attacker $attacker"
            }
            val actual = currentBlocks.orEmpty().filter { it.attacker == attacker }.map(BlockAssignment::blocker)
            require(order.size == actual.size && order.toSet() == actual.toSet()) {
                "CR 509.2: $attacker's order $order must be a permutation of its blockers $actual"
            }
            require(order.size >= MINIMUM_ORDERED_BLOCKERS) {
                "CR 509.2: only an attacker blocked by two or more creatures is ordered, $attacker has ${order.size}"
            }
        }
    }

    private companion object {
        /** CR 509.2: only an attacker with two or more blockers has its blockers ordered. */
        const val MINIMUM_ORDERED_BLOCKERS: Int = 2
    }
}
