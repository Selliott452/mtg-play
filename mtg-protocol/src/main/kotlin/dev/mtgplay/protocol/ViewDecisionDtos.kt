package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.rules.DecisionRequestKind
import dev.mtgplay.rules.DecisionView
import dev.mtgplay.rules.PendingRevealView
import dev.mtgplay.rules.PendingTriggerView
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire mirrors of the trigger, reveal, and decision-context per-seat view nouns (ADR-007): the
 * definition-free pending-trigger projection, the publicly revealed cards, and the decision
 * filtered to the deciding seat (others see only who decides and the broad kind).
 */

/** Wire form of a [PendingTriggerView] — a fired trigger's public last-known information (CR 603.3). */
@Serializable
data class PendingTriggerViewDto(
    val sourceId: Long,
    val sourceCard: String,
    val controller: Int,
    val amount: Int,
    val subject: Long?,
)

/** [PendingTriggerView] to its wire form. */
fun PendingTriggerView.toDto(): PendingTriggerViewDto =
    PendingTriggerViewDto(sourceId.value, sourceCard.name, controller.seat, amount, subject?.value)

/** [PendingTriggerViewDto] back to the engine value. */
fun PendingTriggerViewDto.toDomain(): PendingTriggerView =
    PendingTriggerView(ObjectId(sourceId), CardRef(sourceCard), PlayerId(controller), amount, subject?.let(::ObjectId))

/**
 * Wire form of a [PendingRevealView] — the revealed cards and the keeps gathered so far, both public
 * to both seats (CR 701.16). [kept] is non-empty only part-way through a multi-keep clause
 * (Kruphix's Insight's "up to three"), so it defaults to empty on the wire.
 */
@Serializable
data class PendingRevealViewDto(
    val decider: Int,
    val revealed: List<GameObjectDto>,
    val kept: List<GameObjectDto> = emptyList(),
)

/** [PendingRevealView] to its wire form. */
fun PendingRevealView.toDto(): PendingRevealViewDto =
    PendingRevealViewDto(decider.seat, revealed.map { it.toDto() }, kept.map { it.toDto() })

/** [PendingRevealViewDto] back to the engine value. */
fun PendingRevealViewDto.toDomain(): PendingRevealView =
    PendingRevealView(PlayerId(decider), revealed.map { it.toDomain() }, kept.map { it.toDomain() })

/** Wire form of the broad decision kind a non-deciding seat sees (ADR-007); names mirror [DecisionRequestKind]. */
@Serializable
enum class DecisionRequestKindDto {
    CHOOSE_ACTION,
    CHOOSE_DISCARDS,
    CHOOSE_TARGETS,
    CHOOSE_PAYMENT_PLAN,
    DECLARE_ATTACKERS,
    DECLARE_BLOCKERS,
    ORDER_BLOCKERS,
    ASSIGN_TRAMPLE_DAMAGE,
    ORDER_TRIGGERS,
    CHOOSE_YES_NO,
    CHOOSE_CARDS_TO_EXILE,
    CHOOSE_SACRIFICES,
    CHOOSE_CARDS_TO_DISCARD_FOR_COST,
    CHOOSE_SACRIFICES_FOR_COST,
    CHOOSE_ABILITY_SACRIFICE,
    CHOOSE_MULLIGAN,
    CHOOSE_CARDS_TO_BOTTOM,
    CHOOSE_ABILITY_DISCARD,
    CHOOSE_COLOR,
    CHOOSE_OPTIONAL_DISCARD,
    CHOOSE_FROM_REVEALED,
    CHOOSE_REPLACEMENT,
    CHOOSE_COST_MODE,
    CHOOSE_OPTIONAL_COST_OBJECT,
    CHOOSE_RESOLUTION_DISCARDS,
    CHOOSE_FROM_LIBRARY,
    CHOOSE_LIBRARY_ARRANGEMENT,

    /** A resolving counter's "unless its controller pays" (CR 118.3a). */
    CHOOSE_COUNTER_PAYMENT,
}

/**
 * [DecisionRequestKind] to its wire form. Mapped by name (the two enums are name-identical by
 * construction); the compile-time break on a new request kind is delivered by
 * [dev.mtgplay.rules.kindOf] and the full [DecisionRequestDto] mapping, and a round-trip test pins
 * every value.
 */
fun DecisionRequestKind.toDto(): DecisionRequestKindDto = DecisionRequestKindDto.valueOf(name)

/** [DecisionRequestKindDto] back to the engine value. */
fun DecisionRequestKindDto.toDomain(): DecisionRequestKind = DecisionRequestKind.valueOf(name)

/** Wire form of a [DecisionView] (ADR-007): the deciding seat's full request, or another seat's kind-only view. */
@Serializable
sealed interface DecisionViewDto {
    /** The viewer is the deciding seat: its full request (ADR-005). */
    @Serializable
    @SerialName("to_decide")
    data class ToDecide(
        val request: DecisionRequestDto,
    ) : DecisionViewDto

    /** Another seat is deciding: only who decides and the broad kind. */
    @Serializable
    @SerialName("elsewhere")
    data class Elsewhere(
        val seat: Int,
        val kind: DecisionRequestKindDto,
    ) : DecisionViewDto
}

/** [DecisionView] to its wire form. */
fun DecisionView.toDto(): DecisionViewDto =
    when (this) {
        is DecisionView.ToDecide -> DecisionViewDto.ToDecide(request.toDto())
        is DecisionView.Elsewhere -> DecisionViewDto.Elsewhere(seat.seat, kind.toDto())
    }

/** [DecisionViewDto] back to the engine value. */
fun DecisionViewDto.toDomain(): DecisionView =
    when (this) {
        is DecisionViewDto.ToDecide -> DecisionView.ToDecide(request.toDomain())
        is DecisionViewDto.Elsewhere -> DecisionView.Elsewhere(PlayerId(seat), kind.toDomain())
    }
