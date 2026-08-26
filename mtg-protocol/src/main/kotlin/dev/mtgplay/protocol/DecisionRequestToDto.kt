package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.rules.decision.DecisionRequest

/*
 * Engine -> DTO half of the exhaustive [DecisionRequest] mapping (ADR-008 amendment). Dispatch is
 * grouped by the six sealed families so every `when` stays flat and a new request kind breaks
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
                minimumBlockers.map { BlockerMinimumDto(it.attacker.value, it.attackerCard.name, it.minimum) },
            )
        is DecisionRequest.ChooseYesNo ->
            DecisionRequestDto.ChooseYesNo(id.toDto(), prompt, cardObjectId.value, card.name)
        is DecisionRequest.SingleOptionSelection -> singleOptionSelectionToDto(this)
        is DecisionRequest.SizedSelection -> sizedSelectionToDto(this)
        is DecisionRequest.RangedSelection -> rangedSelectionToDto(this)
        is DecisionRequest.SummedSelection -> summedSelectionToDto(this)
        is DecisionRequest.PermutationSelection -> permutationSelectionToDto(this)
        is DecisionRequest.ChoiceCountSelection -> choiceCountSelectionToDto(this)
        is DecisionRequest.MulliganRequest -> mulliganRequestToDto(this)
    }

/**
 * The ranged subset family — a multi-target choice (CR 601.2c) or an untargeted mid-resolution
 * permanent selection (CR 609.4).
 */
private fun rangedSelectionToDto(request: DecisionRequest.RangedSelection): DecisionRequestDto =
    when (request) {
        is DecisionRequest.ChooseModes ->
            DecisionRequestDto.ChooseModes(
                request.id.toDto(),
                request.cardObjectId.value,
                request.card.name,
                request.options.map { ModeOptionDto(it.modeIndex, it.text) },
                request.minimumCount,
                request.maximumCount,
            )
        is DecisionRequest.ChooseMultipleTargets ->
            DecisionRequestDto.ChooseMultipleTargets(
                request.id.toDto(),
                request.cardObjectId.value,
                request.card.name,
                request.options.map { it.toDto() },
                request.minimumCount,
                request.maximumCount,
            )
        is DecisionRequest.ChoosePermanentsToAffect ->
            DecisionRequestDto.ChoosePermanentsToAffect(
                request.id.toDto(),
                request.sourceCard.name,
                request.prompt,
                request.options.map { cardOption(it.objectId, it.card) },
                request.minimumCount,
                request.maximumCount,
            )
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
                request.cost.render(),
                request.options.map { it.toDto() },
            )
        is DecisionRequest.ChooseXValue ->
            DecisionRequestDto.ChooseXValue(
                request.id.toDto(),
                request.cardObjectId.value,
                request.card.name,
                request.values,
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
        is DecisionRequest.ChooseReplacement,
        is DecisionRequest.ChooseCounterPayment,
        is DecisionRequest.ChooseRevealedHandCard,
        is DecisionRequest.ChooseLibraryArrangement,
        is DecisionRequest.ChooseTapOrUntap,
        is DecisionRequest.ChooseOptionalManaPayment,
        is DecisionRequest.ChooseGraveyardCardToExile,
        is DecisionRequest.ChooseLibraryPosition,
        is DecisionRequest.ChooseExploreDestination,
        is DecisionRequest.ChooseRevealedCardType,
        -> laterSingleOptionSelectionToDto(request)
    }

/**
 * The single-option requests added after the family outgrew one function — the split
 * [costSizedSelectionToDto] already makes for its own family, applied here for the same reason.
 */
private fun laterSingleOptionSelectionToDto(request: DecisionRequest.SingleOptionSelection): DecisionRequestDto =
    when (request) {
        is DecisionRequest.ChooseCounterPayment ->
            DecisionRequestDto.ChooseCounterPayment(
                request.id.toDto(),
                request.card.name,
                request.cost.render(),
                request.options.map { it.toDto() },
            )
        is DecisionRequest.ChooseRevealedHandCard ->
            DecisionRequestDto.ChooseRevealedHandCard(
                request.id.toDto(),
                request.revealer.seat,
                request.sourceCard.name,
                request.options.map { cardOption(it.objectId, it.card) },
            )
        is DecisionRequest.ChooseTapOrUntap ->
            DecisionRequestDto.ChooseTapOrUntap(
                request.id.toDto(),
                request.cardObjectId.value,
                request.card.name,
                request.targetId.value,
                request.options.map { it.toDto() },
            )
        is DecisionRequest.ChooseLibraryArrangement ->
            DecisionRequestDto.ChooseLibraryArrangement(
                request.id.toDto(),
                request.prompt,
                request.pool.map { cardOption(it.objectId, it.card) },
                request.options.map { LibraryArrangementDto(it.toHand, it.toTop, it.toBottom, it.toGraveyard) },
            )
        is DecisionRequest.ChooseReplacement ->
            DecisionRequestDto.ChooseReplacement(
                request.id.toDto(),
                request.options.map { ReplacementOptionDto(it.description) },
            )
        else -> resolutionClauseToDto(request)
    }

/**
 * The non-cost fixed-size subset selections (CR 514.1 / 601.3b / 601.2c / 701.7a); the cost ones route to
 * [costSizedSelectionToDto], the mirror of [costSizedSelectionToDomain]'s split.
 */
private fun sizedSelectionToDto(request: DecisionRequest.SizedSelection): DecisionRequestDto =
    when (request) {
        is DecisionRequest.ChooseDiscards ->
            DecisionRequestDto.ChooseDiscards(
                request.id.toDto(),
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
        is DecisionRequest.ChooseOpponentDiscards ->
            DecisionRequestDto.ChooseOpponentDiscards(
                request.id.toDto(),
                request.controller.seat,
                request.sourceCard.name,
                request.options.map { cardOption(it.objectId, it.card) },
                request.count,
            )
        is DecisionRequest.ChooseOpponentSacrifice ->
            DecisionRequestDto.ChooseOpponentSacrifice(
                request.id.toDto(),
                request.controller.seat,
                request.sourceCard.name,
                request.greatestPowerOnly,
                request.options.map { cardOption(it.objectId, it.card) },
            )
        is DecisionRequest.ChooseCardsToExile,
        is DecisionRequest.ChooseSacrifices,
        is DecisionRequest.ChooseTapsForCost,
        is DecisionRequest.ChooseOptionalCostSacrifice,
        is DecisionRequest.ChooseCostPowerSource,
        is DecisionRequest.ChooseCardsToDiscardForCost,
        is DecisionRequest.ChooseSacrificesForCost,
        is DecisionRequest.ChooseAbilitySacrifice,
        is DecisionRequest.ChooseAbilityDiscard,
        is DecisionRequest.ChooseAbilityReturn,
        -> costSizedSelectionToDto(request)
    }

/**
 * The **cast**-side cost-paying fixed-size subset selections (CR 601.2b/h) — an additional exile,
 * sacrifice, or discard for a spell being cast. Every one has the same wire shape: the object being
 * cast, its printed name, the enumerated options, and the count.
 *
 * The **activation**-side ones route on to [abilityCostSelectionToDto], in its own file. The split is by
 * which object the request names — a cast names `cardObjectId`, an activation `sourceObjectId` — which
 * is a real distinction, though it was the function-length budget that forced the question when
 * `FW-TAPUNTAP` added a third activation-side member. [laterSingleOptionSelectionToDto] made the same
 * move for its own family.
 */
private fun costSizedSelectionToDto(request: DecisionRequest.SizedSelection): DecisionRequestDto =
    when (request) {
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
        is DecisionRequest.ChooseOptionalCostSacrifice,
        is DecisionRequest.ChooseTapsForCost,
        is DecisionRequest.ChooseCostPowerSource,
        -> nonManaCostSelectionToDto(request)
        is DecisionRequest.ChooseCardsToDiscardForCost ->
            DecisionRequestDto.ChooseCardsToDiscardForCost(
                request.id.toDto(),
                request.cardObjectId.value,
                request.card.name,
                request.options.map { cardOption(it.objectId, it.card) },
                request.count,
            )
        is DecisionRequest.ChooseSacrificesForCost ->
            DecisionRequestDto.ChooseSacrificesForCost(
                request.id.toDto(),
                request.cardObjectId.value,
                request.card.name,
                request.options.map { cardOption(it.objectId, it.card) },
                request.count,
            )
        is DecisionRequest.ChooseAbilitySacrifice,
        is DecisionRequest.ChooseAbilityDiscard,
        is DecisionRequest.ChooseAbilityReturn,
        -> abilityCostSelectionToDto(request)
        is DecisionRequest.ChooseDiscards,
        is DecisionRequest.ChooseOptionalDiscard,
        is DecisionRequest.ChooseOptionalCostObject,
        is DecisionRequest.ChooseResolutionDiscards,
        is DecisionRequest.ChooseOpponentDiscards,
        is DecisionRequest.ChooseOpponentSacrifice,
        -> error("CR 601.2: non-cost sized selection routed to the cost helper: $request")
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
                request.optionalSearch,
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

/**
 * One card-in-a-zone option to its shared wire shape (object id plus printed name). `internal` rather
 * than private since `FW-TAPUNTAP` split the activation-side cost selections into their own file, which
 * maps the same option shape.
 */
internal fun cardOption(
    objectId: ObjectId,
    card: CardRef,
): CardObjectOptionDto = CardObjectOptionDto(objectId.value, card.name)
