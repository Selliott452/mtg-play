package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.rules.decision.PriorityOption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire form of one [PriorityOption] (ADR-005) — an action the player holding priority may take.
 * Sealed to mirror [PriorityOption]'s five members exhaustively.
 */
@Serializable
sealed interface PriorityOptionDto {
    /** Pass priority (CR 117.3d). */
    @Serializable
    @SerialName("pass")
    data object Pass : PriorityOptionDto

    /** Begin casting [objectId] from [source] (CR 601.2), optionally via [permission]. */
    @Serializable
    @SerialName("cast_spell")
    data class CastSpell(
        val objectId: Long,
        val card: String,
        val source: CastSourceDto,
        val permission: CastingPermissionDto?,
    ) : PriorityOptionDto

    /** Play the land [objectId] from hand (CR 116.2a). */
    @Serializable
    @SerialName("play_land")
    data class PlayLand(
        val objectId: Long,
        val card: String,
    ) : PriorityOptionDto

    /** Plot the card [objectId] from hand (CR 702.140). */
    @Serializable
    @SerialName("plot_card")
    data class PlotCard(
        val objectId: Long,
        val card: String,
    ) : PriorityOptionDto

    /** Activate the [abilityIndex]th ability of [objectId] functioning from [scope] (CR 602.1). */
    @Serializable
    @SerialName("activate_ability")
    data class ActivateAbility(
        val objectId: Long,
        val card: String,
        val abilityIndex: Int,
        val scope: AbilityZoneScopeDto,
    ) : PriorityOptionDto
}

/** [PriorityOption] to its wire form. */
fun PriorityOption.toDto(): PriorityOptionDto =
    when (this) {
        PriorityOption.Pass -> PriorityOptionDto.Pass
        is PriorityOption.CastSpell ->
            PriorityOptionDto.CastSpell(objectId.value, card.name, source.toDto(), permission?.toDto())
        is PriorityOption.PlayLand -> PriorityOptionDto.PlayLand(objectId.value, card.name)
        is PriorityOption.PlotCard -> PriorityOptionDto.PlotCard(objectId.value, card.name)
        is PriorityOption.ActivateAbility ->
            PriorityOptionDto.ActivateAbility(objectId.value, card.name, abilityIndex, scope.toDto())
    }

/** [PriorityOptionDto] back to the engine value. */
fun PriorityOptionDto.toDomain(): PriorityOption =
    when (this) {
        PriorityOptionDto.Pass -> PriorityOption.Pass
        is PriorityOptionDto.CastSpell ->
            PriorityOption.CastSpell(ObjectId(objectId), CardRef(card), source.toDomain(), permission?.toDomain())
        is PriorityOptionDto.PlayLand -> PriorityOption.PlayLand(ObjectId(objectId), CardRef(card))
        is PriorityOptionDto.PlotCard -> PriorityOption.PlotCard(ObjectId(objectId), CardRef(card))
        is PriorityOptionDto.ActivateAbility ->
            PriorityOption.ActivateAbility(ObjectId(objectId), CardRef(card), abilityIndex, scope.toDomain())
    }
