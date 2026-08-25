package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.rules.decision.DecisionRequest

/*
 * The DTO -> engine mapping of the "pick exactly one of these options" family
 * ([DecisionRequestDto.SingleOptionSelectionDto]): a modal cast's mode (CR 601.2b) and its targets
 * (CR 601.2c), a payment plan (CR 601.2g), a trample assignment (CR 702.19e), an as-enters colour
 * (CR 614.12), a replacement ordering (CR 616.1), a private look's arrangement (CR 701.17a), and a
 * counter's unless-pay (CR 118.3a).
 *
 * Split from DecisionRequestToDomain.kt, which owns the top-level family dispatch, so each file stays
 * inside detekt's function budget — the same split `mtg-rules` made between DecisionApplication.kt and
 * SingleOptionApplication.kt, and for the same reason: this is the family that grows, because almost
 * every new decision an engine framework adds turns out to be "pick one of these".
 */

/** One "pick exactly one of these options" request back to the engine value. */
internal fun singleOptionSelectionToDomain(dto: DecisionRequestDto.SingleOptionSelectionDto): DecisionRequest =
    when (dto) {
        is DecisionRequestDto.ChooseCounterPayment ->
            DecisionRequest.ChooseCounterPayment(
                dto.id.toDomain(),
                CardRef(dto.card),
                ManaCost.parse(dto.cost),
                dto.options.map { it.toDomain() },
            )
        // CR 601.2b: the printed mode index travels as-is; it is not the option index (`FW-MODAL`).
        is DecisionRequestDto.ChooseModes ->
            DecisionRequest.ChooseModes(
                dto.id.toDomain(),
                ObjectId(dto.cardObjectId),
                CardRef(dto.card),
                dto.options.map { DecisionRequest.ChooseModes.Option(it.modeIndex, it.text) },
            )
        is DecisionRequestDto.ChooseTargets ->
            DecisionRequest.ChooseTargets(
                dto.id.toDomain(),
                ObjectId(dto.cardObjectId),
                CardRef(dto.card),
                dto.options.map { it.toDomain() },
            )
        is DecisionRequestDto.ChoosePaymentPlan -> paymentPlanToDomain(dto)
        is DecisionRequestDto.ChooseXValue -> xValueToDomain(dto)
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
        is DecisionRequestDto.ChooseLibraryArrangement -> libraryArrangementToDomain(dto)
        is DecisionRequestDto.ChooseRevealedHandCard ->
            DecisionRequest.ChooseRevealedHandCard(
                dto.id.toDomain(),
                PlayerId(dto.revealer),
                CardRef(dto.sourceCard),
                dto.options.mapOptions { o, c -> DecisionRequest.ChooseRevealedHandCard.Option(o, c) },
            )
        is DecisionRequestDto.ChooseTapOrUntap -> tapOrUntapToDomain(dto)
    }

/**
 * A resolving clause's tap-or-untap answer (CR 608.2c) back to the engine value. Its own function for
 * [libraryArrangementToDomain]'s reason: the family is at detekt's length budget.
 */
private fun tapOrUntapToDomain(dto: DecisionRequestDto.ChooseTapOrUntap): DecisionRequest.ChooseTapOrUntap =
    DecisionRequest.ChooseTapOrUntap(
        dto.id.toDomain(),
        ObjectId(dto.cardObjectId),
        CardRef(dto.card),
        ObjectId(dto.targetId),
        dto.options.map { it.toDomain() },
    )

/**
 * A cast's payment choice (CR 601.2g) back to the engine value. Its own function for the reason
 * [libraryArrangementToDomain] is: the family is at detekt's length budget, and the two cost-shaped
 * branches are the ones that grow.
 */
private fun paymentPlanToDomain(dto: DecisionRequestDto.ChoosePaymentPlan): DecisionRequest.ChoosePaymentPlan =
    DecisionRequest.ChoosePaymentPlan(
        dto.id.toDomain(),
        ObjectId(dto.cardObjectId),
        CardRef(dto.card),
        ManaCost.parse(dto.cost),
        dto.options.map { it.toDomain() },
    )

/**
 * The CR 601.2b announcement of a variable cost (CR 107.3b) back to the engine value. The values are
 * announceable *numbers* and travel as such; the answering index is a position in the list, which is
 * why the list itself is on the wire rather than a count.
 */
private fun xValueToDomain(dto: DecisionRequestDto.ChooseXValue): DecisionRequest.ChooseXValue =
    DecisionRequest.ChooseXValue(
        dto.id.toDomain(),
        ObjectId(dto.cardObjectId),
        CardRef(dto.card),
        dto.values,
    )

/**
 * A private look's arrangement (CR 701.14a, CR 701.17a) back to the engine value. Its own function
 * because it is the family's longest branch — a pool *and* a list of whole arrangements — and inlining
 * it puts [singleOptionSelectionToDomain] over detekt's length budget.
 */
private fun libraryArrangementToDomain(
    dto: DecisionRequestDto.ChooseLibraryArrangement,
): DecisionRequest.ChooseLibraryArrangement =
    DecisionRequest.ChooseLibraryArrangement(
        dto.id.toDomain(),
        dto.prompt,
        dto.pool.mapOptions { o, c -> DecisionRequest.ChooseLibraryArrangement.PoolCard(o, c) },
        dto.options.map {
            DecisionRequest.ChooseLibraryArrangement.Option(it.toHand, it.toTop, it.toBottom, it.toGraveyard)
        },
    )
