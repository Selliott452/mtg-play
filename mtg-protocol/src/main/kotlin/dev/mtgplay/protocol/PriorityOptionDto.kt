package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.rules.decision.PriorityOption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire form of one [PriorityOption] (ADR-005) — an action the player holding priority may take.
 * Sealed to mirror [PriorityOption]'s six members exhaustively.
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

    /**
     * Activate the ninjutsu ability of the hand card [objectId], returning the unblocked attacker
     * [returnedAttacker] to its owner's hand as part of the cost (CR 702.49a).
     *
     * Its own discriminator rather than an [ActivateAbility] index, because ninjutsu is synthesized by
     * the engine from a `Ninjutsu` declaration and is not one of the source's declared activated
     * abilities — there is no index that would name it. It also carries a chosen cost object, which no
     * other activation option does: the (ninja, attacker) pair is enumerated as one option so an agent
     * picks the whole action by a single stable index (ADR-005).
     */
    @Serializable
    @SerialName("activate_ninjutsu")
    data class ActivateNinjutsu(
        val objectId: Long,
        val card: String,
        val returnedAttacker: Long,
        val returnedAttackerCard: String,
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
        is PriorityOption.ActivateNinjutsu ->
            PriorityOptionDto.ActivateNinjutsu(
                objectId.value,
                card.name,
                returnedAttacker.value,
                returnedAttackerCard.name,
            )
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
        is PriorityOptionDto.ActivateNinjutsu ->
            PriorityOption.ActivateNinjutsu(
                ObjectId(objectId),
                CardRef(card),
                ObjectId(returnedAttacker),
                CardRef(returnedAttackerCard),
            )
    }
