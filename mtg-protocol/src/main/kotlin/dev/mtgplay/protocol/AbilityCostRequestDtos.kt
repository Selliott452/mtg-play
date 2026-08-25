package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.rules.decision.DecisionRequest

/*
 * Both halves of the mapping for the **activation-side** cost selections (CR 602.1, CR 602.2b): the
 * sacrifice, discard, and return-a-permanent an activated ability's cost may demand.
 *
 * They live together, in their own file, for two reasons that point the same way. The split from the
 * cast-side selections is real rather than cosmetic — a cast names the object being cast
 * (`cardObjectId`), an activation names the ability's source (`sourceObjectId`), and every request in
 * this file is of the second kind — and DecisionRequestToDto.kt and DecisionRequestToDomain.kt are both
 * at their function budget, so a third member of this family (`FW-TAPUNTAP`'s
 * [DecisionRequest.ChooseAbilityReturn]) had nowhere to go without one. Keeping the two directions in
 * one file is what makes them obviously each other's mirror.
 */

/**
 * The activation-side cost selections, engine → wire (CR 602.1, CR 602.2b). Every member has the same
 * shape: the ability's source object, its printed name, the enumerated options, and the count.
 *
 * Fails loudly on anything else. Its only caller is `sizedSelectionToDto`, whose exhaustive `when`
 * routes exactly the three members below here, so a fourth arriving without a branch breaks that
 * `when` rather than reaching this error.
 */
internal fun abilityCostSelectionToDto(request: DecisionRequest.SizedSelection): DecisionRequestDto =
    when (request) {
        is DecisionRequest.ChooseAbilitySacrifice ->
            DecisionRequestDto.ChooseAbilitySacrifice(
                request.id.toDto(),
                request.sourceObjectId.value,
                request.card.name,
                request.options.map { cardOption(it.objectId, it.card) },
                request.count,
            )
        is DecisionRequest.ChooseAbilityDiscard ->
            DecisionRequestDto.ChooseAbilityDiscard(
                request.id.toDto(),
                request.sourceObjectId.value,
                request.card.name,
                request.options.map { cardOption(it.objectId, it.card) },
                request.count,
            )
        is DecisionRequest.ChooseAbilityReturn ->
            DecisionRequestDto.ChooseAbilityReturn(
                request.id.toDto(),
                request.sourceObjectId.value,
                request.card.name,
                request.options.map { cardOption(it.objectId, it.card) },
                request.count,
            )
        else -> error("CR 602.1: not an activation-side cost selection: $request")
    }

/** The activation-side cost selections, wire → engine — the exact mirror of [abilityCostSelectionToDto]. */
internal fun abilityCostSelectionToDomain(dto: DecisionRequestDto.SizedSelectionDto): DecisionRequest =
    when (dto) {
        is DecisionRequestDto.ChooseAbilitySacrifice ->
            DecisionRequest.ChooseAbilitySacrifice(
                dto.id.toDomain(),
                ObjectId(dto.sourceObjectId),
                CardRef(dto.card),
                dto.options.mapOptions { o, c -> DecisionRequest.ChooseAbilitySacrifice.Option(o, c) },
                dto.count,
            )
        is DecisionRequestDto.ChooseAbilityDiscard ->
            DecisionRequest.ChooseAbilityDiscard(
                dto.id.toDomain(),
                ObjectId(dto.sourceObjectId),
                CardRef(dto.card),
                dto.options.mapOptions { o, c -> DecisionRequest.ChooseAbilityDiscard.Option(o, c) },
                dto.count,
            )
        is DecisionRequestDto.ChooseAbilityReturn ->
            DecisionRequest.ChooseAbilityReturn(
                dto.id.toDomain(),
                ObjectId(dto.sourceObjectId),
                CardRef(dto.card),
                dto.options.mapOptions { o, c -> DecisionRequest.ChooseAbilityReturn.Option(o, c) },
                dto.count,
            )
        else -> error("CR 602.1: not an activation-side cost selection: $dto")
    }
