package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.rules.SeatView
import kotlinx.serialization.Serializable

/**
 * Wire form of a [SeatView] (ADR-007): the whole per-seat filtered projection. Every field mirrors
 * [SeatView]'s, so the filtering rulings documented there are exactly what crosses the wire; the
 * mapping is a straight structural translation ([toDto]/[toDomain]) with no further filtering.
 *
 * [cards] is keyed by printed card **name**, the string form of a [CardRef], so the table is a plain
 * JSON object; the scope of what it contains is [SeatView.cards]'s ruling, not this layer's.
 */
@Serializable
data class SeatViewDto(
    val viewer: Int,
    val cards: Map<String, PrintedCardViewDto>,
    val players: List<PlayerViewDto>,
    val battlefield: List<GameObjectDto>,
    val stack: List<StackEntryViewDto>,
    val exile: List<GameObjectDto>,
    val turn: TurnDto,
    val pendingDecision: DecisionViewDto?,
    val pendingCast: PendingCastDto?,
    val pendingTriggers: List<PendingTriggerViewDto>,
    val pendingMadness: PendingMadnessDto?,
    val pendingReplacement: PendingReplacementDto?,
    val pendingMulligan: PendingMulliganDto?,
    val pendingPlot: PendingPlotDto?,
    val pendingColorChoice: PendingColorChoiceDto?,
    val pendingActivation: PendingActivationDto?,
    val pendingReveal: PendingRevealViewDto?,
    val pendingOptionalDiscardDraw: PendingOptionalDiscardDrawDto?,
    val pendingOptionalCostDraw: PendingOptionalCostDrawDto?,
    val pendingResolutionDiscard: PendingResolutionDiscardDto?,
    val pendingLibrarySearch: PendingLibrarySearchDto?,
    val pendingLibraryLook: PendingLibraryLookViewDto?,
    val pendingTriggerTargets: PendingTriggerTargetsDto?,
    val pendingCounterPayment: PendingCounterPaymentDto?,
    val pendingHandReveal: PendingHandRevealViewDto?,
    val pendingOpponentDiscard: PendingOpponentDiscardViewDto?,
    val pendingRebound: PendingReboundDto?,
    val pendingNinjutsu: PendingNinjutsuDto?,
    val pendingOptionalDraw: PendingOptionalDrawDto?,
    val pendingOptionalTrigger: PendingOptionalTriggerDto?,
    val pendingTapOrUntap: PendingTapOrUntapDto? = null,
    val pendingPermanentSelection: PendingPermanentSelectionDto? = null,
    val timedEffects: List<TimedContinuousEffectDto>,
)

/** [SeatView] to its wire form. */
fun SeatView.toDto(): SeatViewDto =
    SeatViewDto(
        viewer = viewer.seat,
        cards = cards.entries.associate { (ref, card) -> ref.name to card.toDto() },
        players = players.map { it.toDto() },
        battlefield = battlefield.map { it.toDto() },
        stack = stack.map { it.toDto() },
        exile = exile.map { it.toDto() },
        turn = turn.toDto(),
        pendingDecision = pendingDecision?.toDto(),
        pendingCast = pendingCast?.toDto(),
        pendingTriggers = pendingTriggers.map { it.toDto() },
        pendingMadness = pendingMadness?.toDto(),
        pendingReplacement = pendingReplacement?.toDto(),
        pendingMulligan = pendingMulligan?.toDto(),
        pendingPlot = pendingPlot?.toDto(),
        pendingColorChoice = pendingColorChoice?.toDto(),
        pendingActivation = pendingActivation?.toDto(),
        pendingReveal = pendingReveal?.toDto(),
        pendingOptionalDiscardDraw = pendingOptionalDiscardDraw?.toDto(),
        pendingOptionalCostDraw = pendingOptionalCostDraw?.toDto(),
        pendingResolutionDiscard = pendingResolutionDiscard?.toDto(),
        pendingLibrarySearch = pendingLibrarySearch?.toDto(),
        pendingLibraryLook = pendingLibraryLook?.toDto(),
        pendingTriggerTargets = pendingTriggerTargets?.toDto(),
        pendingCounterPayment = pendingCounterPayment?.toDto(),
        pendingHandReveal = pendingHandReveal?.toDto(),
        pendingOpponentDiscard = pendingOpponentDiscard?.toDto(),
        pendingRebound = pendingRebound?.toDto(),
        pendingNinjutsu = pendingNinjutsu?.toDto(),
        pendingOptionalDraw = pendingOptionalDraw?.toDto(),
        pendingOptionalTrigger = pendingOptionalTrigger?.toDto(),
        pendingTapOrUntap = pendingTapOrUntap?.toDto(),
        pendingPermanentSelection = pendingPermanentSelection?.toDto(),
        timedEffects = timedEffects.map { it.toDto() },
    )

/** [SeatViewDto] back to the engine value. */
fun SeatViewDto.toDomain(): SeatView =
    SeatView(
        viewer = PlayerId(viewer),
        cards = cards.entries.associate { (name, card) -> CardRef(name) to card.toDomain() },
        players = players.map { it.toDomain() },
        battlefield = battlefield.map { it.toDomain() },
        stack = stack.map { it.toDomain() },
        exile = exile.map { it.toDomain() },
        turn = turn.toDomain(),
        pendingDecision = pendingDecision?.toDomain(),
        pendingCast = pendingCast?.toDomain(),
        pendingTriggers = pendingTriggers.map { it.toDomain() },
        pendingMadness = pendingMadness?.toDomain(),
        pendingReplacement = pendingReplacement?.toDomain(),
        pendingMulligan = pendingMulligan?.toDomain(),
        pendingPlot = pendingPlot?.toDomain(),
        pendingColorChoice = pendingColorChoice?.toDomain(),
        pendingActivation = pendingActivation?.toDomain(),
        pendingReveal = pendingReveal?.toDomain(),
        pendingOptionalDiscardDraw = pendingOptionalDiscardDraw?.toDomain(),
        pendingOptionalCostDraw = pendingOptionalCostDraw?.toDomain(),
        pendingResolutionDiscard = pendingResolutionDiscard?.toDomain(),
        pendingLibrarySearch = pendingLibrarySearch?.toDomain(),
        pendingLibraryLook = pendingLibraryLook?.toDomain(),
        pendingTriggerTargets = pendingTriggerTargets?.toDomain(),
        pendingCounterPayment = pendingCounterPayment?.toDomain(),
        pendingHandReveal = pendingHandReveal?.toDomain(),
        pendingOpponentDiscard = pendingOpponentDiscard?.toDomain(),
        pendingRebound = pendingRebound?.toDomain(),
        pendingNinjutsu = pendingNinjutsu?.toDomain(),
        pendingOptionalDraw = pendingOptionalDraw?.toDomain(),
        pendingOptionalTrigger = pendingOptionalTrigger?.toDomain(),
        pendingTapOrUntap = pendingTapOrUntap?.toDomain(),
        pendingPermanentSelection = pendingPermanentSelection?.toDomain(),
        timedEffects = timedEffects.map { it.toDomain() },
    )
