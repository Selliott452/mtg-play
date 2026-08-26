package dev.mtgplay.protocol

import dev.mtgplay.rules.decision.DecisionRequest

/**
 * The single-option requests a *resolving* object opens — an optional mana payment (CR 601.3b), a
 * graveyard exile (CR 701.3a), an owner's library position (CR 401.1), and a revealed-card type choice
 * (CR 609.4). A third function in the same chain, split for the same detekt reason the second was.
 */
internal fun resolutionClauseToDto(request: DecisionRequest.SingleOptionSelection): DecisionRequestDto =
    when (request) {
        is DecisionRequest.ChooseOptionalManaPayment ->
            DecisionRequestDto.ChooseOptionalManaPayment(
                request.id.toDto(),
                request.sourceCard.name,
                request.cost.render(),
                request.drawCount,
                request.options.map { it.toDto() },
            )
        is DecisionRequest.ChooseGraveyardCardToExile ->
            DecisionRequestDto.ChooseGraveyardCardToExile(
                request.id.toDto(),
                request.controller.seat,
                request.sourceCard.name,
                request.options.map { cardOption(it.objectId, it.card) },
                request.optionalExile,
            )
        is DecisionRequest.ChooseLibraryPosition ->
            DecisionRequestDto.ChooseLibraryPosition(
                request.id.toDto(),
                request.controller.seat,
                request.sourceCard.name,
                request.permanent.value,
                request.permanentCard.name,
                request.options.map { it.name },
            )
        is DecisionRequest.ChooseRevealedCardType ->
            DecisionRequestDto.ChooseRevealedCardType(
                request.id.toDto(),
                request.sourceCard.name,
                request.revealCount,
                request.options.map { it.name },
            )
        is DecisionRequest.ChooseDungeonRoom ->
            DecisionRequestDto.ChooseDungeonRoom(
                request.id.toDto(),
                request.dungeon,
                request.fromRoom,
                request.options.map { DungeonRoomOptionDto(it.room, it.name) },
            )
        else -> error("not a resolution-clause single-option request: $request")
    }
