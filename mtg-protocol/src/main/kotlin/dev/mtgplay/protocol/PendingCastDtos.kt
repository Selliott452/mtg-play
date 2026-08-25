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

/**
 * Wire form of [PendingCast] (CR 601.2): a cast gathering its choices.
 *
 * [chosenModes] carries the modal half (CR 601.2b, `FW-MODAL`) and is listed **before** [chosenTargets]
 * because that is the order the engine settles them in: a modal spell's targeting line is not determined
 * until its mode is, so a view showing a non-null [chosenModes] and a null [chosenTargets] is a cast
 * paused exactly between CR 601.2b and CR 601.2c.
 */
@Serializable
data class PendingCastDto(
    val caster: Int,
    val cardObjectId: Long,
    val chosenModes: List<Int>?,
    val chosenTargets: List<TargetDto>?,
    val source: CastSourceDto,
    val castingPermission: CastingPermissionDto?,
    val additionalExileCost: List<Long>?,
    val sacrificeCost: List<Long>?,
    val additionalDiscard: List<Long>?,
    val additionalSacrifice: List<Long>?,
)

/** [PendingCast] to its wire form. */
fun PendingCast.toDto(): PendingCastDto =
    PendingCastDto(
        caster = caster.seat,
        cardObjectId = cardObjectId.value,
        chosenModes = chosenModes,
        chosenTargets = chosenTargets?.map { it.toDto() },
        source = source.toDto(),
        castingPermission = castingPermission?.toDto(),
        additionalExileCost = additionalExileCost?.map(ObjectId::value),
        sacrificeCost = sacrificeCost?.map(ObjectId::value),
        additionalDiscard = additionalDiscard?.map(ObjectId::value),
        additionalSacrifice = additionalSacrifice?.map(ObjectId::value),
    )

/** [PendingCastDto] back to the engine value. */
fun PendingCastDto.toDomain(): PendingCast =
    PendingCast(
        caster = PlayerId(caster),
        cardObjectId = ObjectId(cardObjectId),
        chosenModes = chosenModes?.toPersistentList(),
        chosenTargets = chosenTargets?.map { it.toDomain() }?.toPersistentList(),
        source = source.toDomain(),
        castingPermission = castingPermission?.toDomain(),
        additionalExileCost = additionalExileCost?.map(::ObjectId)?.toPersistentList(),
        sacrificeCost = sacrificeCost?.map(::ObjectId)?.toPersistentList(),
        additionalDiscard = additionalDiscard?.map(::ObjectId)?.toPersistentList(),
        additionalSacrifice = additionalSacrifice?.map(::ObjectId)?.toPersistentList(),
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
    val chosenSacrifice: List<Long>?,
    val chosenReturn: List<Long>? = null,
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
        chosenSacrifice = chosenSacrifice?.map(ObjectId::value),
        // CR 602.1 (`FW-TAPUNTAP`): the "return a permanent you control" cost's chosen object. Null
        // means "not yet answered" and an empty list "settled, nothing to choose" — the same
        // three-valued shape the sibling cost selections use, so it must ride the wire rather than
        // being reconstructed, or a paused activation would decode to a *different* gathering stage.
        chosenReturn = chosenReturn?.map(ObjectId::value),
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
        chosenSacrifice = chosenSacrifice?.map(::ObjectId)?.toPersistentList(),
        chosenReturn = chosenReturn?.map(::ObjectId)?.toPersistentList(),
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
