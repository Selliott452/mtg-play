package dev.mtgplay.protocol

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.PendingLibrarySearch
import dev.mtgplay.core.state.PendingOptionalCostDraw
import dev.mtgplay.core.state.PendingOptionalDiscardDraw
import dev.mtgplay.core.state.PendingResolutionDiscard
import kotlinx.serialization.Serializable

/*
 * Wire mirrors of the mid-resolution pending nouns a [SeatView] carries: each carries only the
 * deciding seat and small scalars; the actual card options are the deciding seat's private request,
 * so a library search exposes only that it is in progress (never the matching cards).
 */

/** Wire form of [PendingOptionalDiscardDraw] (CR 601.3b). */
@Serializable
data class PendingOptionalDiscardDrawDto(
    val decider: Int,
    val drawCount: Int,
    val awaitingDiscard: Boolean,
)

/** [PendingOptionalDiscardDraw] to its wire form. */
fun PendingOptionalDiscardDraw.toDto(): PendingOptionalDiscardDrawDto =
    PendingOptionalDiscardDrawDto(decider.seat, drawCount, awaitingDiscard)

/** [PendingOptionalDiscardDrawDto] back to the engine value. */
fun PendingOptionalDiscardDrawDto.toDomain(): PendingOptionalDiscardDraw =
    PendingOptionalDiscardDraw(PlayerId(decider), drawCount, awaitingDiscard)

/** Wire form of [PendingOptionalCostDraw] (CR 601.3b). */
@Serializable
data class PendingOptionalCostDrawDto(
    val decider: Int,
    val chosenMode: OptionalCostModeDto?,
)

/** [PendingOptionalCostDraw] to its wire form. */
fun PendingOptionalCostDraw.toDto(): PendingOptionalCostDrawDto =
    PendingOptionalCostDrawDto(decider.seat, chosenMode?.toDto())

/** [PendingOptionalCostDrawDto] back to the engine value. */
fun PendingOptionalCostDrawDto.toDomain(): PendingOptionalCostDraw =
    PendingOptionalCostDraw(PlayerId(decider), chosenMode?.toDomain())

/** Wire form of [PendingResolutionDiscard] (CR 601.2c). */
@Serializable
data class PendingResolutionDiscardDto(
    val decider: Int,
    val count: Int,
)

/** [PendingResolutionDiscard] to its wire form. */
fun PendingResolutionDiscard.toDto(): PendingResolutionDiscardDto = PendingResolutionDiscardDto(decider.seat, count)

/** [PendingResolutionDiscardDto] back to the engine value. */
fun PendingResolutionDiscardDto.toDomain(): PendingResolutionDiscard =
    PendingResolutionDiscard(PlayerId(decider), count)

/** Wire form of [PendingLibrarySearch] (CR 701.18) — only the searching seat; the options stay secret. */
@Serializable
data class PendingLibrarySearchDto(
    val decider: Int,
)

/** [PendingLibrarySearch] to its wire form. */
fun PendingLibrarySearch.toDto(): PendingLibrarySearchDto = PendingLibrarySearchDto(decider.seat)

/** [PendingLibrarySearchDto] back to the engine value. */
fun PendingLibrarySearchDto.toDomain(): PendingLibrarySearch = PendingLibrarySearch(PlayerId(decider))
