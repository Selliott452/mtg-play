package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.rules.decision.DecisionRequest

/*
 * Engine -> DTO half of the exhaustive [DecisionRequest] mapping (ADR-008 amendment). Dispatch is
 * grouped by the five sealed families so every `when` stays flat and a new request kind breaks
 * compilation. The DTO -> engine half is in DecisionRequestToDomain.kt.
 */

/** [DecisionRequest] to its wire form. */
fun DecisionRequest.toDto(): DecisionRequestDto =
    when (this) {
        is DecisionRequest.ChooseAction ->
            DecisionRequestDto.ChooseAction(id.toDto(), options.map { it.toDto() })
        is DecisionRequest.DeclareAttackers ->
            DecisionRequestDto.DeclareAttackers(
                id.toDto(),
                options.map { AttackerOptionDto(it.attacker.value, it.card.name, it.defendingPlayer.seat) },
            )
        is DecisionRequest.DeclareBlockers ->
            DecisionRequestDto.DeclareBlockers(
                id.toDto(),
                options.map {
                    BlockerOptionDto(it.blocker.value, it.blockerCard.name, it.attacker.value, it.attackerCard.name)
                },
            )
        is DecisionRequest.ChooseYesNo ->
            DecisionRequestDto.ChooseYesNo(id.toDto(), prompt, cardObjectId.value, card.name)
        is DecisionRequest.SingleOptionSelection -> singleOptionSelectionToDto(this)
        is DecisionRequest.SizedSelection -> sizedSelectionToDto(this)
        is DecisionRequest.PermutationSelection -> permutationSelectionToDto(this)
        is DecisionRequest.ChoiceCountSelection -> choiceCountSelectionToDto(this)
        is DecisionRequest.MulliganRequest -> mulliganRequestToDto(this)
    }

/** The "pick exactly one of these options" family (CR 601.2c/601.2g/702.19e/614.12/616.1/701.17a). */
private fun singleOptionSelectionToDto(request: DecisionRequest.SingleOptionSelection): DecisionRequestDto =
    when (request) {
        is DecisionRequest.ChooseTargets ->
            DecisionRequestDto.ChooseTargets(
                request.id.toDto(),
                request.cardObjectId.value,
                request.card.name,
                request.options.map { it.toDto() },
            )
        is DecisionRequest.ChoosePaymentPlan ->
            DecisionRequestDto.ChoosePaymentPlan(
                request.id.toDto(),
                request.cardObjectId.value,
                request.card.name,
                request.options.map { it.toDto() },
            )
        is DecisionRequest.AssignTrampleDamage ->
            DecisionRequestDto.AssignTrampleDamage(
                request.id.toDto(),
                request.attacker.value,
                request.attackerCard.name,
                request.defendingPlayer.seat,
                request.options,
            )
        is DecisionRequest.ChooseColor ->
            DecisionRequestDto.ChooseColor(
                request.id.toDto(),
                request.cardObjectId.value,
                request.card.name,
                request.options.map { it.toDto() },
            )
        is DecisionRequest.ChooseReplacement ->
            DecisionRequestDto.ChooseReplacement(
                request.id.toDto(),
                request.options.map { ReplacementOptionDto(it.description) },
            )
        is DecisionRequest.ChooseLibraryArrangement ->
            DecisionRequestDto.ChooseLibraryArrangement(
                request.id.toDto(),
                request.prompt,
                request.pool.map { cardOption(it.objectId, it.card) },
                request.options.map { LibraryArrangementDto(it.toHand, it.toTop, it.toBottom) },
            )
    }

private fun sizedSelectionToDto(request: DecisionRequest.SizedSelection): DecisionRequestDto =
    when (request) {
        is DecisionRequest.ChooseDiscards ->
            DecisionRequestDto.ChooseDiscards(
                request.id.toDto(),
                request.options.map { cardOption(it.objectId, it.card) },
                request.count,
            )
        is DecisionRequest.ChooseCardsToExile ->
            DecisionRequestDto.ChooseCardsToExile(
                request.id.toDto(),
                request.cardObjectId.value,
                request.card.name,
                request.options.map { cardOption(it.objectId, it.card) },
                request.count,
            )
        is DecisionRequest.ChooseSacrifices ->
            DecisionRequestDto.ChooseSacrifices(
                request.id.toDto(),
                request.cardObjectId.value,
                request.card.name,
                request.options.map { cardOption(it.objectId, it.card) },
                request.count,
            )
        is DecisionRequest.ChooseCardsToDiscardForCost ->
            DecisionRequestDto.ChooseCardsToDiscardForCost(
                request.id.toDto(),
                request.cardObjectId.value,
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
        is DecisionRequest.ChooseOptionalDiscard ->
            DecisionRequestDto.ChooseOptionalDiscard(
                request.id.toDto(),
                request.options.map { cardOption(it.objectId, it.card) },
                request.count,
            )
        is DecisionRequest.ChooseOptionalCostObject ->
            DecisionRequestDto.ChooseOptionalCostObject(
                request.id.toDto(),
                request.options.map { cardOption(it.objectId, it.card) },
            )
        is DecisionRequest.ChooseResolutionDiscards ->
            DecisionRequestDto.ChooseResolutionDiscards(
                request.id.toDto(),
                request.options.map { cardOption(it.objectId, it.card) },
                request.count,
            )
    }

private fun permutationSelectionToDto(request: DecisionRequest.PermutationSelection): DecisionRequestDto =
    when (request) {
        is DecisionRequest.OrderBlockers ->
            DecisionRequestDto.OrderBlockers(
                request.id.toDto(),
                request.attacker.value,
                request.options.map { cardOption(it.blocker, it.card) },
            )
        is DecisionRequest.OrderTriggers ->
            DecisionRequestDto.OrderTriggers(
                request.id.toDto(),
                request.options.map { TriggerOptionDto(it.sourceCard.name, it.description) },
            )
    }

private fun choiceCountSelectionToDto(request: DecisionRequest.ChoiceCountSelection): DecisionRequestDto =
    when (request) {
        is DecisionRequest.ChooseFromRevealed ->
            DecisionRequestDto.ChooseFromRevealed(
                request.id.toDto(),
                request.options.map { cardOption(it.objectId, it.card) },
            )
        is DecisionRequest.ChooseCostMode ->
            DecisionRequestDto.ChooseCostMode(request.id.toDto(), request.prompt, request.options.map { it.toDto() })
        is DecisionRequest.ChooseFromLibrary ->
            DecisionRequestDto.ChooseFromLibrary(
                request.id.toDto(),
                request.options.map { cardOption(it.objectId, it.card) },
            )
    }

private fun mulliganRequestToDto(request: DecisionRequest.MulliganRequest): DecisionRequestDto =
    when (request) {
        is DecisionRequest.ChooseMulligan ->
            DecisionRequestDto.ChooseMulligan(request.id.toDto(), request.mulligansTaken)
        is DecisionRequest.ChooseCardsToBottom ->
            DecisionRequestDto.ChooseCardsToBottom(
                request.id.toDto(),
                request.options.map { cardOption(it.objectId, it.card) },
                request.count,
            )
    }

/** One card-in-a-zone option to its shared wire shape (object id plus printed name). */
private fun cardOption(
    objectId: ObjectId,
    card: CardRef,
): CardObjectOptionDto = CardObjectOptionDto(objectId.value, card.name)
