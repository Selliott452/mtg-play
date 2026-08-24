package dev.mtgplay.protocol

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.Target
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire form of one [Target] (CR 115.1): a targeted player (seat index), a battlefield permanent
 * (object id), or a spell on the stack (object id). Sealed to mirror [Target] exhaustively.
 *
 * The two object-id members are deliberately distinct wire kinds rather than one `object_target`: they
 * name ids in different zones, and a peer rendering a target must know which zone to look in.
 */
@Serializable
sealed interface TargetDto {
    /** A targeted player (CR 115.1a). */
    @Serializable
    @SerialName("player_target")
    data class PlayerTarget(
        val seat: Int,
    ) : TargetDto

    /** A targeted battlefield permanent (CR 115.1b), by object id. */
    @Serializable
    @SerialName("permanent_target")
    data class PermanentTarget(
        val objectId: Long,
    ) : TargetDto

    /** A targeted spell on the stack (CR 115.1, CR 111.1), by its stack-residence object id. */
    @Serializable
    @SerialName("spell_on_stack_target")
    data class SpellOnStackTarget(
        val objectId: Long,
    ) : TargetDto
}

/** [Target] to its wire form. */
fun Target.toDto(): TargetDto =
    when (this) {
        is Target.Player -> TargetDto.PlayerTarget(id.seat)
        is Target.Permanent -> TargetDto.PermanentTarget(id.value)
        is Target.SpellOnStack -> TargetDto.SpellOnStackTarget(id.value)
    }

/** [TargetDto] back to the engine value. */
fun TargetDto.toDomain(): Target =
    when (this) {
        is TargetDto.PlayerTarget -> Target.Player(PlayerId(seat))
        is TargetDto.PermanentTarget -> Target.Permanent(ObjectId(objectId))
        is TargetDto.SpellOnStackTarget -> Target.SpellOnStack(ObjectId(objectId))
    }
