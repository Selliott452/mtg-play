package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.rules.PendingLibraryLookView
import dev.mtgplay.rules.PendingOpponentDiscardView
import kotlinx.serialization.Serializable

/*
 * Wire mirrors of the *count-only* pending nouns (ADR-007): the two pauses whose choice is made over
 * cards one seat may see and the others may not, so no projection of them names a card for any seat —
 * the deciding one included. A private library look (CR 701.14a) is the case seen from the decider's
 * side, an each-opponent discard (CR 701.7a) the case seen from the controller's; both resolve the
 * same way, by leaving the identities in exactly one place, the deciding seat's own request options.
 *
 * That is why they sit together and apart from the records in PendingResolutionDtos.kt: those are
 * count-only because there is nothing more to say, these because saying more would leak.
 */

/**
 * Wire form of [PendingLibraryLookView] (CR 701.14a) — the *count-only* projection of a private look.
 * Neither the looked-at identities nor their object ids appear here or anywhere else a non-deciding seat
 * can read; they reach the deciding seat only as its own request's options
 * (docs/design/library-look.md §3).
 */
@Serializable
data class PendingLibraryLookViewDto(
    val decider: Int,
    val source: LibraryLookSourceDto,
    val count: Int,
    val awaitingShuffle: Boolean,
)

/** [PendingLibraryLookView] to its wire form. */
fun PendingLibraryLookView.toDto(): PendingLibraryLookViewDto =
    PendingLibraryLookViewDto(decider.seat, source.toDto(), count, awaitingShuffle)

/** [PendingLibraryLookViewDto] back to the engine value. */
fun PendingLibraryLookViewDto.toDomain(): PendingLibraryLookView =
    PendingLibraryLookView(PlayerId(decider), source.toDomain(), count, awaitingShuffle)

/**
 * Wire form of [PendingOpponentDiscardView] (CR 701.7a) — the *count-only* projection of an open "each
 * opponent discards a card". Added by `FW-NONCTRLDEC`.
 *
 * No card identity appears here, for **any** seat including the deciding one: the decider is choosing
 * from their own hand, which the resolving object's controller may not see (CR 402.1). The options exist
 * in exactly one place, the deciding seat's own
 * [dev.mtgplay.rules.decision.DecisionRequest.ChooseOpponentDiscards], and this is deliberately not that
 * place — the same ruling [PendingLibraryLookViewDto] records from the other side.
 */
@Serializable
data class PendingOpponentDiscardViewDto(
    val decider: Int,
    val controller: Int,
    val count: Int,
    val remainingCount: Int,
    val sourceCard: String,
)

/** [PendingOpponentDiscardView] to its wire form. */
fun PendingOpponentDiscardView.toDto(): PendingOpponentDiscardViewDto =
    PendingOpponentDiscardViewDto(decider.seat, controller.seat, count, remainingCount, sourceCard.name)

/** [PendingOpponentDiscardViewDto] back to the engine value. */
fun PendingOpponentDiscardViewDto.toDomain(): PendingOpponentDiscardView =
    PendingOpponentDiscardView(PlayerId(decider), PlayerId(controller), count, remainingCount, CardRef(sourceCard))
