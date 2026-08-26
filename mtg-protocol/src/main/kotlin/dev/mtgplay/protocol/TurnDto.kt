package dev.mtgplay.protocol

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.AttackerAssignment
import dev.mtgplay.core.state.BlockAssignment
import dev.mtgplay.core.state.CombatState
import dev.mtgplay.core.state.Turn
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.serialization.Serializable

/**
 * Wire form of the [Turn] (CR 500): the active player, turn number, phase/step, land drops, and the
 * combat state. All public.
 *
 * @property activePlayer the active player's seat index (CR 102.1).
 * @property number the turn number.
 * @property phase the current phase (CR 500.1).
 * @property step the current step, or `null` in a main phase (CR 505).
 * @property landsPlayedThisTurn how many lands have been played this turn (CR 305.2).
 * @property spellsCastThisTurn how many spells every player together has cast this turn (CR 601.2i) —
 *   the number storm reads (CR 702.40a). Public: every cast is announced to the table. Additive
 *   (`W9-C`), and required rather than defaulted, because a seat that could not see it could not value
 *   a storm card in hand.
 * @property combat the combat progress, or `null` outside combat (CR 506.1).
 */
@Serializable
data class TurnDto(
    val activePlayer: Int,
    val number: Int,
    val phase: TurnPhaseDto,
    val step: TurnStepDto?,
    val landsPlayedThisTurn: Int,
    val spellsCastThisTurn: Int,
    val combat: CombatStateDto?,
)

/** Wire form of one attacker declaration (CR 508.1). */
@Serializable
data class AttackerAssignmentDto(
    val attacker: Long,
    val defendingPlayer: Int,
)

/** Wire form of one block (CR 509.1). */
@Serializable
data class BlockAssignmentDto(
    val blocker: Long,
    val attacker: Long,
)

/**
 * Wire form of the [CombatState] (CR 506–511): who attacks and blocks whom, the declared blocked
 * attackers, the per-attacker blocker order and trample assignments, and how far the damage steps
 * have progressed. All public.
 *
 * @property attackers the declared attackers, in declaration order.
 * @property blocks the declared blocks, or `null` before blockers are declared.
 * @property blockedAttackers the attackers that became blocked (CR 509.1h), as object ids.
 * @property blockerOrder the per-attacker damage-assignment order (CR 509.2).
 * @property trampleAssignments the per-attacker trample amount assigned to the defender (CR 702.19).
 * @property firstStrikeDamageDealt whether the first-strike damage step has happened.
 * @property regularDamageDealt whether the regular damage step has happened.
 */
@Serializable
data class CombatStateDto(
    val attackers: List<AttackerAssignmentDto>,
    val blocks: List<BlockAssignmentDto>?,
    val blockedAttackers: List<Long>,
    val blockerOrder: Map<Long, List<Long>>,
    val trampleAssignments: Map<Long, Int>,
    val firstStrikeDamageDealt: Boolean,
    val regularDamageDealt: Boolean,
)

/** [Turn] to its wire form. */
fun Turn.toDto(): TurnDto =
    TurnDto(
        activePlayer = activePlayer.seat,
        number = number,
        phase = phase.toDto(),
        step = step?.toDto(),
        landsPlayedThisTurn = landsPlayedThisTurn,
        spellsCastThisTurn = spellsCastThisTurn,
        combat = combat?.toDto(),
    )

/** [TurnDto] back to the engine value. */
fun TurnDto.toDomain(): Turn =
    Turn(
        activePlayer = PlayerId(activePlayer),
        number = number,
        phase = phase.toDomain(),
        step = step?.toDomain(),
        landsPlayedThisTurn = landsPlayedThisTurn,
        spellsCastThisTurn = spellsCastThisTurn,
        combat = combat?.toDomain(),
    )

/** [CombatState] to its wire form. */
fun CombatState.toDto(): CombatStateDto =
    CombatStateDto(
        attackers = attackers.map { AttackerAssignmentDto(it.attacker.value, it.defendingPlayer.seat) },
        blocks = blocks?.map { BlockAssignmentDto(it.blocker.value, it.attacker.value) },
        blockedAttackers = blockedAttackers.map(ObjectId::value),
        blockerOrder =
            blockerOrder.entries.associate { (attacker, order) ->
                attacker.value to order.map(ObjectId::value)
            },
        trampleAssignments =
            trampleAssignments.entries.associate { (attacker, amount) -> attacker.value to amount },
        firstStrikeDamageDealt = firstStrikeDamageDealt,
        regularDamageDealt = regularDamageDealt,
    )

/** [CombatStateDto] back to the engine value. */
fun CombatStateDto.toDomain(): CombatState =
    CombatState(
        attackers =
            attackers
                .map { AttackerAssignment(ObjectId(it.attacker), PlayerId(it.defendingPlayer)) }
                .toPersistentList(),
        blocks = blocks?.map { BlockAssignment(ObjectId(it.blocker), ObjectId(it.attacker)) }?.toPersistentList(),
        blockedAttackers = blockedAttackers.map(::ObjectId).toPersistentSet(),
        blockerOrder =
            blockerOrder.entries
                .associate { (attacker, order) -> ObjectId(attacker) to order.map(::ObjectId).toPersistentList() }
                .toPersistentMap(),
        trampleAssignments =
            trampleAssignments.entries
                .associate { (attacker, amount) -> ObjectId(attacker) to amount }
                .toPersistentMap(),
        firstStrikeDamageDealt = firstStrikeDamageDealt,
        regularDamageDealt = regularDamageDealt,
    )
