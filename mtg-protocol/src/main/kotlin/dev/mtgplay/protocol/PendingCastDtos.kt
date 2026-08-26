package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.ChosenPowerSource
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
 *
 * [kicked] and [chosenX] are the two CR 601.2b cost announcements (`FW-OPTCOST`, `FW-X`), listed last
 * because that is where the engine settles them — after every other cost selection, so their
 * affordability bounds are priced against the same reservation the payment plan will use
 * (docs/design/mana-payment.md §12). A view with both non-null and no plan chosen yet is a cast paused
 * exactly at CR 601.2g.
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
    val tapCost: List<Long>? = null,
    val optionalCostTaken: Boolean? = null,
    val optionalCostObjects: List<Long>? = null,
    val additionalDiscard: List<Long>?,
    val additionalSacrifice: List<Long>?,
    val costPowerSource: List<PowerSourceOptionDto>? = null,
    val kicked: Boolean?,
    val chosenX: Int?,
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
        tapCost = tapCost?.map(ObjectId::value),
        optionalCostTaken = optionalCostTaken,
        optionalCostObjects = optionalCostObjects?.map(ObjectId::value),
        additionalDiscard = additionalDiscard?.map(ObjectId::value),
        additionalSacrifice = additionalSacrifice?.map(ObjectId::value),
        // CR 601.2b (`W9-D`): what a non-consuming additional cost named. Null means "not yet answered"
        // and an empty list "settled, no such cost" — the same three-valued shape the sibling selections
        // use, so it must ride the wire or a paused cast would decode to a *different* gathering stage.
        // The [PowerSourceOptionDto.power] field is not read back: it is the request's display value, and
        // a cast record carries the *identity* it named, not the number it had at the time.
        costPowerSource = costPowerSource?.map { it.toOptionDto() },
        kicked = kicked,
        chosenX = chosenX,
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
        tapCost = tapCost?.map(::ObjectId)?.toPersistentList(),
        optionalCostTaken = optionalCostTaken,
        optionalCostObjects = optionalCostObjects?.map(::ObjectId)?.toPersistentList(),
        additionalDiscard = additionalDiscard?.map(::ObjectId)?.toPersistentList(),
        additionalSacrifice = additionalSacrifice?.map(::ObjectId)?.toPersistentList(),
        costPowerSource = costPowerSource?.map { it.toChosenPowerSource() }?.toPersistentList(),
        kicked = kicked,
        chosenX = chosenX,
    )

/**
 * One [ChosenPowerSource] on the wire, reusing [PowerSourceOptionDto] because the payload is the same
 * value — a `kind` word plus whichever half applies (`W9-D`). The `power` field is written as `0` here
 * and ignored on the way back: it is display data belonging to the *request*, not to the cast record.
 */
private fun ChosenPowerSource.toOptionDto(): PowerSourceOptionDto =
    when (this) {
        is ChosenPowerSource.ChosenCreature -> PowerSourceOptionDto(CHOSEN_CREATURE, objectId.value, "", 0)
        is ChosenPowerSource.RevealedCard -> PowerSourceOptionDto(REVEALED_CARD, null, card.name, 0)
    }

/** The decode mirror of [toOptionDto]; version skew and a missing id both fail loudly. */
private fun PowerSourceOptionDto.toChosenPowerSource(): ChosenPowerSource =
    when (kind) {
        CHOSEN_CREATURE ->
            ChosenPowerSource.ChosenCreature(
                ObjectId(requireNotNull(objectId) { "CR 601.2b: a chosen creature on the wire must name its object" }),
            )
        REVEALED_CARD -> ChosenPowerSource.RevealedCard(CardRef(card))
        else -> error("unknown power source \"$kind\" on the wire; this engine knows $CHOSEN_CREATURE and $REVEALED_CARD")
    }

private const val CHOSEN_CREATURE: String = "CHOSEN_CREATURE"

private const val REVEALED_CARD: String = "REVEALED_CARD"

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

/**
 * Wire form of [PendingColorChoice] (CR 614.12).
 *
 * @property decider the seat choosing the colour.
 * @property playedLand the hand card object of a land whose play-land special action the choice
 *   interrupted (CR 305.1) — a Gate — or `null` when a resolving permanent spell is entering. Public:
 *   the play-land action itself is public, so which card is being played is not information the choice
 *   hides.
 */
@Serializable
data class PendingColorChoiceDto(
    val decider: Int,
    val playedLand: Long?,
)

/** [PendingColorChoice] to its wire form. */
fun PendingColorChoice.toDto(): PendingColorChoiceDto = PendingColorChoiceDto(decider.seat, playedLand?.value)

/** [PendingColorChoiceDto] back to the engine value. */
fun PendingColorChoiceDto.toDomain(): PendingColorChoice =
    PendingColorChoice(PlayerId(decider), playedLand?.let(::ObjectId))
