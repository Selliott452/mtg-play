package dev.mtgplay.protocol

import kotlinx.serialization.Serializable

/*
 * The option payloads shared across the decision-request DTOs. The engine models each request's
 * options as its own nested `Option` type; on the wire the interchangeable shapes are unified here,
 * and each request's `toDomain` reconstructs its specific nested type.
 */

/**
 * One card-in-a-zone option (an object id plus its printed name) — the shape shared by every "choose
 * cards from a zone" selection (discards, exiles, sacrifices, mulligan bottoming, reveal/library
 * finds, blocker ordering).
 *
 * @property objectId the object the option refers to.
 * @property card the printed card name, for display.
 */
@Serializable
data class CardObjectOptionDto(
    val objectId: Long,
    val card: String,
)

/**
 * One eligible attacker option (CR 508.1).
 *
 * @property attacker the eligible creature.
 * @property card its printed name.
 * @property defendingPlayer the player it would attack.
 */
@Serializable
data class AttackerOptionDto(
    val attacker: Long,
    val card: String,
    val defendingPlayer: Int,
)

/**
 * One legal (blocker, attacker) pairing option (CR 509.1).
 *
 * @property blocker the defending creature that would block.
 * @property blockerCard the blocker's printed name.
 * @property attacker the declared attacker it would block.
 * @property attackerCard the attacker's printed name.
 */
@Serializable
data class BlockerOptionDto(
    val blocker: Long,
    val blockerCard: String,
    val attacker: Long,
    val attackerCard: String,
)

/**
 * One simultaneous-trigger option to be ordered (CR 603.3b).
 *
 * @property sourceCard the printed name of the trigger's source.
 * @property description a short human description of the trigger.
 */
@Serializable
data class TriggerOptionDto(
    val sourceCard: String,
    val description: String,
)

/**
 * One applicable replacement-effect option (CR 616.1).
 *
 * @property description a short human description of the replacement.
 */
@Serializable
data class ReplacementOptionDto(
    val description: String,
)
