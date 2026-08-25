package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.state.PendingCounterPayment
import dev.mtgplay.core.state.PendingMulligan
import dev.mtgplay.core.state.PendingReplacement
import dev.mtgplay.core.state.PendingTriggerTargets
import kotlinx.serialization.Serializable

/*
 * Wire mirrors of the pre-cast/pre-game choice pending nouns a [SeatView] carries unfiltered: none
 * carries hand contents (the mulligan bottoming and discard selections are the deciding seat's
 * private request options, not stored here). The two free-cast-from-exile offers live together in
 * PendingExileCastDtos.kt.
 */

/** Wire form of [PendingReplacement] (CR 616.1). */
@Serializable
data class PendingReplacementDto(
    val player: Int,
    val objectId: Long,
)

/** [PendingReplacement] to its wire form. */
fun PendingReplacement.toDto(): PendingReplacementDto = PendingReplacementDto(player.seat, objectId.value)

/** [PendingReplacementDto] back to the engine value. */
fun PendingReplacementDto.toDomain(): PendingReplacement = PendingReplacement(PlayerId(player), ObjectId(objectId))

/** Wire form of [PendingMulligan] (CR 103.4/103.5). */
@Serializable
data class PendingMulliganDto(
    val deciding: Int,
    val mulliganCount: Int,
    val stage: MulliganStageDto,
)

/** [PendingMulligan] to its wire form. */
fun PendingMulligan.toDto(): PendingMulliganDto = PendingMulliganDto(deciding.seat, mulliganCount, stage.toDto())

/** [PendingMulliganDto] back to the engine value. */
fun PendingMulliganDto.toDomain(): PendingMulligan =
    PendingMulligan(PlayerId(deciding), mulliganCount, stage.toDomain())

/**
 * Wire form of [PendingTriggerTargets] (CR 603.3d) — a triggered ability choosing its targets as it is
 * put on the stack. Fully public: it names only the ability's controller and its source's last-known
 * id and printed identity, all of which the seat view's pending-trigger list already discloses.
 */
@Serializable
data class PendingTriggerTargetsDto(
    val controller: Int,
    val sourceId: Long,
    val sourceCard: String,
)

/** [PendingTriggerTargets] to its wire form. */
fun PendingTriggerTargets.toDto(): PendingTriggerTargetsDto =
    PendingTriggerTargetsDto(controller.seat, sourceId.value, sourceCard.name)

/** [PendingTriggerTargetsDto] back to the engine value. */
fun PendingTriggerTargetsDto.toDomain(): PendingTriggerTargets =
    PendingTriggerTargets(PlayerId(controller), ObjectId(sourceId), CardRef(sourceCard))

/**
 * Wire form of [PendingCounterPayment] (CR 118.3a) — a resolving counter's "unless its controller pays"
 * pause. Fully public: the deciding seat, the amount printed on the counter (as its Scryfall brace
 * string), and the id of a spell sitting face-up on the public stack (CR 405).
 */
@Serializable
data class PendingCounterPaymentDto(
    val decider: Int,
    val cost: String,
    val counteredObjectId: Long,
)

/** [PendingCounterPayment] to its wire form. */
fun PendingCounterPayment.toDto(): PendingCounterPaymentDto =
    PendingCounterPaymentDto(decider.seat, cost.render(), counteredObjectId.value)

/** [PendingCounterPaymentDto] back to the engine value. */
fun PendingCounterPaymentDto.toDomain(): PendingCounterPayment =
    PendingCounterPayment(PlayerId(decider), ManaCost.parse(cost), ObjectId(counteredObjectId))
