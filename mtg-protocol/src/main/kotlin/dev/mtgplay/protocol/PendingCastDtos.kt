package dev.mtgplay.protocol

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.PendingActivation
import dev.mtgplay.core.state.PendingCast
import dev.mtgplay.core.state.PendingColorChoice
import dev.mtgplay.core.state.PendingPlot
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.Serializable

/*
 * Wire mirrors of the action-gathering pending nouns a [SeatView] carries unfiltered (they concern
 * public actions; object ids stay opaque `Long`s, naming no hidden card identity).
 */

/** Wire form of [PendingCast] (CR 601.2): a cast gathering its choices. */
@Serializable
data class PendingCastDto(
    val caster: Int,
    val cardObjectId: Long,
    val chosenTargets: List<TargetDto>?,
    val source: CastSourceDto,
    val castingPermission: CastingPermissionDto?,
    val additionalExileCost: List<Long>?,
    val sacrificeCost: List<Long>?,
    val additionalDiscard: List<Long>?,
)

/** [PendingCast] to its wire form. */
fun PendingCast.toDto(): PendingCastDto =
    PendingCastDto(
        caster = caster.seat,
        cardObjectId = cardObjectId.value,
        chosenTargets = chosenTargets?.map { it.toDto() },
        source = source.toDto(),
        castingPermission = castingPermission?.toDto(),
        additionalExileCost = additionalExileCost?.map(ObjectId::value),
        sacrificeCost = sacrificeCost?.map(ObjectId::value),
        additionalDiscard = additionalDiscard?.map(ObjectId::value),
    )

/** [PendingCastDto] back to the engine value. */
fun PendingCastDto.toDomain(): PendingCast =
    PendingCast(
        caster = PlayerId(caster),
        cardObjectId = ObjectId(cardObjectId),
        chosenTargets = chosenTargets?.map { it.toDomain() }?.toPersistentList(),
        source = source.toDomain(),
        castingPermission = castingPermission?.toDomain(),
        additionalExileCost = additionalExileCost?.map(::ObjectId)?.toPersistentList(),
        sacrificeCost = sacrificeCost?.map(::ObjectId)?.toPersistentList(),
        additionalDiscard = additionalDiscard?.map(::ObjectId)?.toPersistentList(),
    )

/** Wire form of [PendingActivation] (CR 602.2). */
@Serializable
data class PendingActivationDto(
    val activator: Int,
    val sourceObjectId: Long,
    val source: AbilityZoneScopeDto,
    val abilityIndex: Int,
    val chosenDiscard: List<Long>?,
    val chosenTargets: List<TargetDto>?,
)

/** [PendingActivation] to its wire form. */
fun PendingActivation.toDto(): PendingActivationDto =
    PendingActivationDto(
        activator = activator.seat,
        sourceObjectId = sourceObjectId.value,
        source = source.toDto(),
        abilityIndex = abilityIndex,
        chosenDiscard = chosenDiscard?.map(ObjectId::value),
        chosenTargets = chosenTargets?.map { it.toDto() },
    )

/** [PendingActivationDto] back to the engine value. */
fun PendingActivationDto.toDomain(): PendingActivation =
    PendingActivation(
        activator = PlayerId(activator),
        sourceObjectId = ObjectId(sourceObjectId),
        source = source.toDomain(),
        abilityIndex = abilityIndex,
        chosenDiscard = chosenDiscard?.map(::ObjectId)?.toPersistentList(),
        chosenTargets = chosenTargets?.map { it.toDomain() }?.toPersistentList(),
    )

/** Wire form of [PendingPlot] (CR 702.140) — the fact and the plotting seat; the card stays in hand. */
@Serializable
data class PendingPlotDto(
    val caster: Int,
    val cardObjectId: Long,
)

/** [PendingPlot] to its wire form. */
fun PendingPlot.toDto(): PendingPlotDto = PendingPlotDto(caster.seat, cardObjectId.value)

/** [PendingPlotDto] back to the engine value. */
fun PendingPlotDto.toDomain(): PendingPlot = PendingPlot(PlayerId(caster), ObjectId(cardObjectId))

/** Wire form of [PendingColorChoice] (CR 614.12). */
@Serializable
data class PendingColorChoiceDto(
    val decider: Int,
)

/** [PendingColorChoice] to its wire form. */
fun PendingColorChoice.toDto(): PendingColorChoiceDto = PendingColorChoiceDto(decider.seat)

/** [PendingColorChoiceDto] back to the engine value. */
fun PendingColorChoiceDto.toDomain(): PendingColorChoice = PendingColorChoice(PlayerId(decider))
