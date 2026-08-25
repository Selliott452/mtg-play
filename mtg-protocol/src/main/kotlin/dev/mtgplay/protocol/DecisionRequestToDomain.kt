package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
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
        is DecisionRequestDto.SizedSelectionDto -> sizedSelectionToDomain(this)
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
    )

/** The "pick exactly one of these options" family (CR 601.2c/601.2g/702.19e/614.12/616.1/701.17a). */
private fun singleOptionSelectionToDomain(dto: DecisionRequestDto.SingleOptionSelectionDto): DecisionRequest =
    when (dto) {
        is DecisionRequestDto.ChooseCounterPayment ->
            DecisionRequest.ChooseCounterPayment(
                dto.id.toDomain(),
                CardRef(dto.card),
                ManaCost.parse(dto.cost),
                dto.options.map { it.toDomain() },
            )
        is DecisionRequestDto.ChooseTargets ->
            DecisionRequest.ChooseTargets(
                dto.id.toDomain(),
                ObjectId(dto.cardObjectId),
                CardRef(dto.card),
                dto.options.map { it.toDomain() },
            )
        is DecisionRequestDto.ChoosePaymentPlan ->
            DecisionRequest.ChoosePaymentPlan(
                dto.id.toDomain(),
                ObjectId(dto.cardObjectId),
                CardRef(dto.card),
                ManaCost.parse(dto.cost),
                dto.options.map { it.toDomain() },
            )
        is DecisionRequestDto.AssignTrampleDamage ->
            DecisionRequest.AssignTrampleDamage(
                dto.id.toDomain(),
                ObjectId(dto.attacker),
                CardRef(dto.attackerCard),
                PlayerId(dto.defendingPlayer),
                dto.options,
            )
        is DecisionRequestDto.ChooseColor ->
            DecisionRequest.ChooseColor(
                dto.id.toDomain(),
                ObjectId(dto.cardObjectId),
                CardRef(dto.card),
                dto.options.map { it.toDomain() },
            )
        is DecisionRequestDto.ChooseReplacement ->
            DecisionRequest.ChooseReplacement(
                dto.id.toDomain(),
                dto.options.map { DecisionRequest.ChooseReplacement.Option(it.description) },
            )
        is DecisionRequestDto.ChooseRevealedHandCard ->
            DecisionRequest.ChooseRevealedHandCard(
                dto.id.toDomain(),
                PlayerId(dto.revealer),
                CardRef(dto.sourceCard),
                dto.options.mapOptions { o, c -> DecisionRequest.ChooseRevealedHandCard.Option(o, c) },
            )
        is DecisionRequestDto.ChooseLibraryArrangement ->
            DecisionRequest.ChooseLibraryArrangement(
                dto.id.toDomain(),
                dto.prompt,
                dto.pool.mapOptions { o, c -> DecisionRequest.ChooseLibraryArrangement.PoolCard(o, c) },
                dto.options.map { DecisionRequest.ChooseLibraryArrangement.Option(it.toHand, it.toTop, it.toBottom) },
            )
    }

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
        is DecisionRequestDto.ChooseCardsToExile,
        is DecisionRequestDto.ChooseSacrifices,
        is DecisionRequestDto.ChooseCardsToDiscardForCost,
        is DecisionRequestDto.ChooseSacrificesForCost,
        is DecisionRequestDto.ChooseAbilitySacrifice,
        is DecisionRequestDto.ChooseAbilityDiscard,
        -> costSizedSelectionToDomain(dto)
    }

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
        is DecisionRequestDto.ChooseDiscards,
        is DecisionRequestDto.ChooseOptionalDiscard,
        is DecisionRequestDto.ChooseOptionalCostObject,
        is DecisionRequestDto.ChooseResolutionDiscards,
        is DecisionRequestDto.ChooseOpponentDiscards,
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
private inline fun <T> List<CardObjectOptionDto>.mapOptions(factory: (ObjectId, CardRef) -> T): List<T> =
    map { factory(ObjectId(it.objectId), CardRef(it.card)) }
