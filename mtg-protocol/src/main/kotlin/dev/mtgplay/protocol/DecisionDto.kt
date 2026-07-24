package dev.mtgplay.protocol

import dev.mtgplay.rules.decision.Decision
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire form of a [Decision] (ADR-004/ADR-005): a player's answer to a request — the echoed
 * [requestId] plus the selected stable index(es). Sealed to mirror [Decision]'s two shapes.
 */
@Serializable
sealed interface DecisionDto {
    /** The identity of the request this decision answers (ADR-004). */
    val requestId: DecisionRequestIdDto

    /** A single-select answer: the option at [index]. */
    @Serializable
    @SerialName("single_select")
    data class SingleSelect(
        override val requestId: DecisionRequestIdDto,
        val index: Int,
    ) : DecisionDto

    /** A multi-select answer: the options at [indices], in application order. */
    @Serializable
    @SerialName("multi_select")
    data class MultiSelect(
        override val requestId: DecisionRequestIdDto,
        val indices: List<Int>,
    ) : DecisionDto
}

/** [Decision] to its wire form. */
fun Decision.toDto(): DecisionDto =
    when (this) {
        is Decision.SingleSelect -> DecisionDto.SingleSelect(requestId.toDto(), index)
        is Decision.MultiSelect -> DecisionDto.MultiSelect(requestId.toDto(), indices)
    }

/** [DecisionDto] back to the engine value. */
fun DecisionDto.toDomain(): Decision =
    when (this) {
        is DecisionDto.SingleSelect -> Decision.SingleSelect(requestId.toDomain(), index)
        is DecisionDto.MultiSelect -> Decision.MultiSelect(requestId.toDomain(), indices)
    }
