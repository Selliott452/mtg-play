package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.rules.decision.DecisionRequest

/*
 * Both halves of the mapping for the **summed-weight** selection family (CR 601.2b, CR 701.60a) —
 * collect evidence (`W9-B`).
 *
 * Its own file for the reason `NonManaCostRequestDtos.kt` and `AbilityCostRequestDtos.kt` are: the two
 * top-level codec files are at their function budget, and keeping a family's encode and decode side by
 * side is what makes them obviously each other's mirror.
 *
 * **What is new on the wire, and why it has to be.** Every other selection family sends bare
 * [CardObjectOptionDto] options, because their legality is a property of the option *count* and a client
 * can compute that from the list length. A summed selection cannot: the answer's legality depends on the
 * options' mana values, so each option carries its own [WeightedCardOptionDto.weight] and the request
 * carries the threshold. A client that ignored the weights could not construct a legal answer at all,
 * which is exactly the enumerate-then-reject failure ADR-005 forbids on the engine side and the protocol
 * must not reintroduce on the wire.
 */

/** The summed-weight selection family, engine → wire (CR 601.2b, CR 701.60a). */
internal fun summedSelectionToDto(request: DecisionRequest.SummedSelection): DecisionRequestDto =
    when (request) {
        is DecisionRequest.ChooseEvidence ->
            DecisionRequestDto.ChooseEvidence(
                request.id.toDto(),
                request.cardObjectId.value,
                request.card.name,
                request.options.map { WeightedCardOptionDto(it.objectId.value, it.card.name, it.manaValue) },
                request.requiredTotal,
            )
    }

/** The decode mirror of [summedSelectionToDto] (CR 601.2b, CR 701.60a). */
internal fun summedSelectionToDomain(dto: DecisionRequestDto.SummedSelectionDto): DecisionRequest =
    when (dto) {
        is DecisionRequestDto.ChooseEvidence ->
            DecisionRequest.ChooseEvidence(
                dto.id.toDomain(),
                ObjectId(dto.cardObjectId),
                CardRef(dto.card),
                dto.options.map {
                    DecisionRequest.ChooseEvidence.Option(ObjectId(it.objectId), CardRef(it.card), it.weight)
                },
                dto.requiredTotal,
            )
    }
