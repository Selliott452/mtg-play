package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.rules.decision.DecisionRequest

/*
 * DTO -> engine half of the exhaustive [DecisionRequest] mapping (ADR-008 amendment). Dispatch is
 * grouped by the five sealed families so every `when` stays flat and a new request kind breaks
 * compilation. The engine -> DTO half is in DecisionRequestToDto.kt.
 */

/** [DecisionRequestDto] back to the engine value. */
fun DecisionRequestDto.toDomain(): DecisionRequest =
    when (this) {
        is DecisionRequestDto.ChooseAction ->
            DecisionRequest.ChooseAction(id.toDomain(), options.map { it.toDomain() })
        is DecisionRequestDto.DeclareAttackers -> declareAttackersToDomain(this)
        is DecisionRequestDto.DeclareBlockers -> declareBlockersToDomain(this)
        is DecisionRequestDto.ChooseYesNo ->
            DecisionRequest.ChooseYesNo(id.toDomain(), prompt, ObjectId(cardObjectId), CardRef(card))
        is DecisionRequestDto.SingleOptionSelectionDto -> singleOptionSelectionToDomain(this)
        is DecisionRequestDto.RangedSelectionDto -> rangedSelectionToDomain(this)
        is DecisionRequestDto.SizedSelectionDto -> sizedSelectionToDomain(this)
        is DecisionRequestDto.SummedSelectionDto -> summedSelectionToDomain(this)
        is DecisionRequestDto.PermutationSelectionDto -> permutationSelectionToDomain(this)
        is DecisionRequestDto.ChoiceCountSelectionDto -> choiceCountSelectionToDomain(this)
        is DecisionRequestDto.MulliganRequestDto -> mulliganRequestToDomain(this)
    }

private fun declareAttackersToDomain(dto: DecisionRequestDto.DeclareAttackers): DecisionRequest =
    DecisionRequest.DeclareAttackers(
        dto.id.toDomain(),
        dto.options.map {
            DecisionRequest.DeclareAttackers.Option(
                ObjectId(it.attacker),
                CardRef(it.card),
                PlayerId(it.defendingPlayer),
            )
        },
    )

private fun declareBlockersToDomain(dto: DecisionRequestDto.DeclareBlockers): DecisionRequest =
    DecisionRequest.DeclareBlockers(
        dto.id.toDomain(),
        dto.options.map {
            DecisionRequest.DeclareBlockers.Option(
                ObjectId(it.blocker),
                CardRef(it.blockerCard),
                ObjectId(it.attacker),
                CardRef(it.attackerCard),
            )
        },
        dto.minimumBlockers.map {
            DecisionRequest.DeclareBlockers.BlockerMinimum(ObjectId(it.attacker), CardRef(it.attackerCard), it.minimum)
        },
    )

private fun sizedSelectionToDomain(dto: DecisionRequestDto.SizedSelectionDto): DecisionRequest =
    when (dto) {
        is DecisionRequestDto.ChooseDiscards ->
            DecisionRequest.ChooseDiscards(
                dto.id.toDomain(),
                dto.options.mapOptions { o, c -> DecisionRequest.ChooseDiscards.Option(o, c) },
                dto.count,
            )
        is DecisionRequestDto.ChooseOptionalDiscard ->
            DecisionRequest.ChooseOptionalDiscard(
                dto.id.toDomain(),
                dto.options.mapOptions { o, c -> DecisionRequest.ChooseOptionalDiscard.Option(o, c) },
                dto.count,
            )
        is DecisionRequestDto.ChooseOptionalCostObject ->
            DecisionRequest.ChooseOptionalCostObject(
                dto.id.toDomain(),
                dto.options.mapOptions { o, c -> DecisionRequest.ChooseOptionalCostObject.Option(o, c) },
            )
        is DecisionRequestDto.ChooseResolutionDiscards ->
            DecisionRequest.ChooseResolutionDiscards(
                dto.id.toDomain(),
                dto.options.mapOptions { o, c -> DecisionRequest.ChooseResolutionDiscards.Option(o, c) },
                dto.count,
            )
        is DecisionRequestDto.ChooseOpponentDiscards ->
            DecisionRequest.ChooseOpponentDiscards(
                dto.id.toDomain(),
                PlayerId(dto.controller),
                CardRef(dto.sourceCard),
                dto.options.mapOptions { o, c -> DecisionRequest.ChooseOpponentDiscards.Option(o, c) },
                dto.count,
            )
        is DecisionRequestDto.ChooseOpponentSacrifice ->
            DecisionRequest.ChooseOpponentSacrifice(
                dto.id.toDomain(),
                PlayerId(dto.controller),
                CardRef(dto.sourceCard),
                dto.greatestPowerOnly,
                dto.options.mapOptions { o, c -> DecisionRequest.ChooseOpponentSacrifice.Option(o, c) },
            )
        is DecisionRequestDto.ChooseCardsToExile,
        is DecisionRequestDto.ChooseSacrifices,
        is DecisionRequestDto.ChooseTapsForCost,
        is DecisionRequestDto.ChooseOptionalCostSacrifice,
        is DecisionRequestDto.ChooseCardsToDiscardForCost,
        is DecisionRequestDto.ChooseSacrificesForCost,
        is DecisionRequestDto.ChooseAbilitySacrifice,
        is DecisionRequestDto.ChooseAbilityDiscard,
        is DecisionRequestDto.ChooseAbilityReturn,
        -> costSizedSelectionToDomain(dto)
    }

/**
 * The **cast**-side cost selections (CR 601.2b/h): an additional exile, sacrifice, or discard for a
 * spell being cast. The activation-side ones route on to [abilityCostSelectionToDomain], split by which
 * object the request names — `cardObjectId` for a cast, `sourceObjectId` for an activation.
 */
private fun costSizedSelectionToDomain(dto: DecisionRequestDto.SizedSelectionDto): DecisionRequest =
    when (dto) {
        is DecisionRequestDto.ChooseCardsToExile ->
            DecisionRequest.ChooseCardsToExile(
                dto.id.toDomain(),
                ObjectId(dto.cardObjectId),
                CardRef(dto.card),
                dto.options.mapOptions { o, c -> DecisionRequest.ChooseCardsToExile.Option(o, c) },
                dto.count,
            )
        is DecisionRequestDto.ChooseSacrifices ->
            DecisionRequest.ChooseSacrifices(
                dto.id.toDomain(),
                ObjectId(dto.cardObjectId),
                CardRef(dto.card),
                dto.options.mapOptions { o, c -> DecisionRequest.ChooseSacrifices.Option(o, c) },
                dto.count,
            )
        is DecisionRequestDto.ChooseOptionalCostSacrifice,
        is DecisionRequestDto.ChooseTapsForCost,
        -> nonManaCostSelectionToDomain(dto)
        is DecisionRequestDto.ChooseCardsToDiscardForCost ->
            DecisionRequest.ChooseCardsToDiscardForCost(
                dto.id.toDomain(),
                ObjectId(dto.cardObjectId),
                CardRef(dto.card),
                dto.options.mapOptions { o, c -> DecisionRequest.ChooseCardsToDiscardForCost.Option(o, c) },
                dto.count,
            )
        is DecisionRequestDto.ChooseSacrificesForCost ->
            DecisionRequest.ChooseSacrificesForCost(
                dto.id.toDomain(),
                ObjectId(dto.cardObjectId),
                CardRef(dto.card),
                dto.options.mapOptions { o, c -> DecisionRequest.ChooseSacrificesForCost.Option(o, c) },
                dto.count,
            )
        is DecisionRequestDto.ChooseAbilitySacrifice,
        is DecisionRequestDto.ChooseAbilityDiscard,
        is DecisionRequestDto.ChooseAbilityReturn,
        -> abilityCostSelectionToDomain(dto)
        is DecisionRequestDto.ChooseDiscards,
        is DecisionRequestDto.ChooseOptionalDiscard,
        is DecisionRequestDto.ChooseOptionalCostObject,
        is DecisionRequestDto.ChooseResolutionDiscards,
        is DecisionRequestDto.ChooseOpponentDiscards,
        is DecisionRequestDto.ChooseOpponentSacrifice,
        -> error("CR 601.2: non-cost sized selection routed to the cost helper: $dto")
    }

private fun permutationSelectionToDomain(dto: DecisionRequestDto.PermutationSelectionDto): DecisionRequest =
    when (dto) {
        is DecisionRequestDto.OrderBlockers ->
            DecisionRequest.OrderBlockers(
                dto.id.toDomain(),
                ObjectId(dto.attacker),
                dto.options.mapOptions { o, c -> DecisionRequest.OrderBlockers.Option(o, c) },
            )
        is DecisionRequestDto.OrderTriggers ->
            DecisionRequest.OrderTriggers(
                dto.id.toDomain(),
                dto.options.map { DecisionRequest.OrderTriggers.Option(CardRef(it.sourceCard), it.description) },
            )
    }

private fun choiceCountSelectionToDomain(dto: DecisionRequestDto.ChoiceCountSelectionDto): DecisionRequest =
    when (dto) {
        is DecisionRequestDto.ChooseFromRevealed ->
            DecisionRequest.ChooseFromRevealed(
                dto.id.toDomain(),
                dto.options.mapOptions { o, c -> DecisionRequest.ChooseFromRevealed.Option(o, c) },
            )
        is DecisionRequestDto.ChooseCostMode ->
            DecisionRequest.ChooseCostMode(dto.id.toDomain(), dto.prompt, dto.options.map { it.toDomain() })
        is DecisionRequestDto.ChooseFromLibrary ->
            DecisionRequest.ChooseFromLibrary(
                dto.id.toDomain(),
                dto.options.mapOptions { o, c -> DecisionRequest.ChooseFromLibrary.Option(o, c) },
                dto.optionalSearch,
            )
    }

private fun mulliganRequestToDomain(dto: DecisionRequestDto.MulliganRequestDto): DecisionRequest =
    when (dto) {
        is DecisionRequestDto.ChooseMulligan -> DecisionRequest.ChooseMulligan(dto.id.toDomain(), dto.mulligansTaken)
        is DecisionRequestDto.ChooseCardsToBottom ->
            DecisionRequest.ChooseCardsToBottom(
                dto.id.toDomain(),
                dto.options.mapOptions { o, c -> DecisionRequest.ChooseCardsToBottom.Option(o, c) },
                dto.count,
            )
    }

/** Maps the shared card-option wire shape to a request's specific nested option via [factory]. */
internal inline fun <T> List<CardObjectOptionDto>.mapOptions(factory: (ObjectId, CardRef) -> T): List<T> =
    map { factory(ObjectId(it.objectId), CardRef(it.card)) }

/**
 * The "pick between N and M of these, by distinct index" family — a multi-target choice (CR 601.2c,
 * `FW-MULTITGT`) or an untargeted mid-resolution permanent selection (CR 609.4, `FW-TAPUNTAP`).
 */
private fun rangedSelectionToDomain(dto: DecisionRequestDto.RangedSelectionDto): DecisionRequest =
    when (dto) {
        is DecisionRequestDto.ChooseMultipleTargets ->
            DecisionRequest.ChooseMultipleTargets(
                dto.id.toDomain(),
                ObjectId(dto.cardObjectId),
                CardRef(dto.card),
                dto.options.map { it.toDomain() },
                dto.minimumCount,
                dto.maximumCount,
            )
        is DecisionRequestDto.ChoosePermanentsToAffect ->
            DecisionRequest.ChoosePermanentsToAffect(
                dto.id.toDomain(),
                CardRef(dto.sourceCard),
                dto.prompt,
                dto.options.mapOptions { o, c -> DecisionRequest.ChoosePermanentsToAffect.Option(o, c) },
                dto.minimumCount,
                dto.maximumCount,
            )
    }
