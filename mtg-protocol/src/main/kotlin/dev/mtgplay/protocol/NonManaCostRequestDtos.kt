package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
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
        else -> error("CR 601.2h: not a permanent-consuming cast cost selection: $dto")
    }
