package dev.mtgplay.protocol

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.rules.decision.DecisionRequestId
import kotlinx.serialization.Serializable

/**
 * Wire form of a [DecisionRequestId] (ADR-004): the stable `(seat, ordinal)` identity a
 * [DecisionDto] echoes back so a decision is matched to exactly the request it answers.
 *
 * @property seat the deciding seat's index.
 * @property ordinal how many decisions the seat had answered when the request was surfaced.
 */
@Serializable
data class DecisionRequestIdDto(
    val seat: Int,
    val ordinal: Int,
)

/** [DecisionRequestId] to its wire form. */
fun DecisionRequestId.toDto(): DecisionRequestIdDto = DecisionRequestIdDto(seat.seat, ordinal)

/** [DecisionRequestIdDto] back to the engine value. */
fun DecisionRequestIdDto.toDomain(): DecisionRequestId = DecisionRequestId(PlayerId(seat), ordinal)
