package dev.mtgplay.acceptance.replay

import dev.mtgplay.core.state.CombatState

/**
 * A canonical descriptor of the in-progress combat (CR 506–511), factored out of [canonicalDescriptor]:
 * attackers (id>defender), blocks (blocker>attacker), the blocked-attacker set (CR 509.1h), the
 * per-attacker damage-assignment orders, the recorded trample assignments (CR 702.19), and the two
 * damage-step flags. Part of the [Fingerprint] pre-image.
 */
internal fun renderCombat(combat: CombatState): String =
    buildString {
        append("atk[")
        append(combat.attackers.joinToString(",") { "${it.attacker.value}>${it.defendingPlayer.seat}" })
        append("]blk[")
        append(combat.blocks?.joinToString(",") { "${it.blocker.value}>${it.attacker.value}" } ?: "-")
        append("]blocked[")
        append(combat.blockedAttackers.joinToString(",") { it.value.toString() })
        append("]ord[")
        append(
            combat.blockerOrder.entries.joinToString(";") { (attacker, order) ->
                "${attacker.value}:${order.joinToString(">") { it.value.toString() }}"
            },
        )
        append("]trample[")
        append(
            combat.trampleAssignments.entries.joinToString(";") { (attacker, toPlayer) ->
                "${attacker.value}:$toPlayer"
            },
        )
        append("]fs=").append(combat.firstStrikeDamageDealt)
        append(",rd=").append(combat.regularDamageDealt)
    }
