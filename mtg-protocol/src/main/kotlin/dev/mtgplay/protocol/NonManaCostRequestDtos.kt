package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.ChosenPowerSource
import dev.mtgplay.rules.decision.DecisionRequest

/*
 * Both halves of the mapping for the two **cast**-side cost selections that consume a *permanent*
 * rather than a card (CR 601.2b/h): an announced bargain ([DecisionRequest.ChooseOptionalCostSacrifice],
 * `FW-BARGAIN`) and a flashback tap cost ([DecisionRequest.ChooseTapsForCost], `FW-PREVENT2`).
 *
 * Their own file for exactly [abilityCostSelectionToDto]'s two reasons, and the precedent is that file:
 * the grouping is real — both name a battlefield object the cast is about to spend, where every other
 * cast-side cost selection names a card in a hand or a graveyard — and DecisionRequestToDto.kt and
 * DecisionRequestToDomain.kt are both at their function budget, so wave 8's two new members had nowhere
 * else to go. Keeping the two directions in one file is what makes them obviously each other's mirror.
 */

/**
 * The permanent-consuming cast cost selections, engine → wire (CR 601.2b/h). Both members have the same
 * shape: the object being cast, its printed name, the enumerated options, and the count.
 *
 * Fails loudly on anything else. Its only caller is `costSizedSelectionToDto`, whose exhaustive `when`
 * routes exactly the two members below here, so a third arriving without a branch breaks that `when`
 * rather than reaching this error.
 */
internal fun nonManaCostSelectionToDto(request: DecisionRequest.SizedSelection): DecisionRequestDto =
    when (request) {
        is DecisionRequest.ChooseOptionalCostSacrifice ->
            DecisionRequestDto.ChooseOptionalCostSacrifice(
                request.id.toDto(),
                request.cardObjectId.value,
                request.card.name,
                request.options.map { cardOption(it.objectId, it.card) },
                request.count,
            )
        is DecisionRequest.ChooseTapsForCost ->
            DecisionRequestDto.ChooseTapsForCost(
                request.id.toDto(),
                request.cardObjectId.value,
                request.card.name,
                request.options.map { cardOption(it.objectId, it.card) },
                request.count,
            )
        // CR 601.2b (`W9-D`): the one member here that consumes nothing, and the one whose options are
        // not all object ids — see [PowerSourceOptionDto].
        is DecisionRequest.ChooseCostPowerSource ->
            DecisionRequestDto.ChooseCostPowerSource(
                request.id.toDto(),
                request.cardObjectId.value,
                request.card.name,
                request.options.map { powerSourceOption(it.source, it.card, it.power) },
            )
        else -> error("CR 601.2h: not a permanent-consuming cast cost selection: $request")
    }

/** The decode mirror of [nonManaCostSelectionToDto] (CR 601.2b/h). */
internal fun nonManaCostSelectionToDomain(dto: DecisionRequestDto.SizedSelectionDto): DecisionRequest =
    when (dto) {
        is DecisionRequestDto.ChooseOptionalCostSacrifice ->
            DecisionRequest.ChooseOptionalCostSacrifice(
                dto.id.toDomain(),
                ObjectId(dto.cardObjectId),
                CardRef(dto.card),
                dto.options.mapOptions { o, c -> DecisionRequest.ChooseOptionalCostSacrifice.Option(o, c) },
                dto.count,
            )
        is DecisionRequestDto.ChooseTapsForCost ->
            DecisionRequest.ChooseTapsForCost(
                dto.id.toDomain(),
                ObjectId(dto.cardObjectId),
                CardRef(dto.card),
                dto.options.mapOptions { o, c -> DecisionRequest.ChooseTapsForCost.Option(o, c) },
                dto.count,
            )
        is DecisionRequestDto.ChooseCostPowerSource ->
            DecisionRequest.ChooseCostPowerSource(
                dto.id.toDomain(),
                ObjectId(dto.cardObjectId),
                CardRef(dto.card),
                dto.options.map {
                    DecisionRequest.ChooseCostPowerSource.Option(
                        powerSourceOf(it.kind, it.objectId, it.card),
                        CardRef(it.card),
                        it.power,
                    )
                },
            )
        else -> error("CR 601.2h: not a permanent-consuming cast cost selection: $dto")
    }

/** One [ChosenPowerSource] on the wire: a `kind` word plus whichever half of the payload applies. */
private fun powerSourceOption(
    source: ChosenPowerSource,
    card: CardRef,
    power: Int,
): PowerSourceOptionDto =
    when (source) {
        is ChosenPowerSource.ChosenCreature ->
            PowerSourceOptionDto(CHOSEN_CREATURE, source.objectId.value, card.name, power)
        is ChosenPowerSource.RevealedCard ->
            PowerSourceOptionDto(REVEALED_CARD, null, source.card.name, power)
    }

/** The [ChosenPowerSource] a wire [kind] names; version skew and a missing id both fail loudly. */
private fun powerSourceOf(
    kind: String,
    objectId: Long?,
    card: String,
): ChosenPowerSource =
    when (kind) {
        CHOSEN_CREATURE ->
            ChosenPowerSource.ChosenCreature(
                ObjectId(
                    requireNotNull(objectId) { "CR 601.2b: a chosen creature on the wire must name its object" },
                ),
            )
        REVEALED_CARD -> ChosenPowerSource.RevealedCard(CardRef(card))
        else ->
            error(
                "unknown power source \"$kind\" on the wire; this engine knows " +
                    "$CHOSEN_CREATURE and $REVEALED_CARD",
            )
    }

private const val CHOSEN_CREATURE: String = "CHOSEN_CREATURE"

private const val REVEALED_CARD: String = "REVEALED_CARD"
