package dev.mtgplay.protocol

import dev.mtgplay.core.definition.PermanentSelectionAction
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.PendingPermanentSelection
import kotlinx.serialization.Serializable

/*
 * The wire forms of the **untargeted mid-resolution permanent selection** (CR 609.4, `FW-TAPUNTAP`):
 * Snap's "Untap up to two lands" and Azorius Chancery's "return a land you control to its owner's
 * hand".
 *
 * The pending record and the action enum share a file rather than being split between
 * PendingResolutionDtos.kt and DefinitionEnumDtos.kt, which is where each would otherwise sit by kind.
 * Two reasons, and the second is the real one: both of those files are at their function budget, and
 * the record is meaningless without the enum — a reader asking "what does this pause do to the
 * permanents it names?" would otherwise have to cross files to find out. PendingHiddenChoiceDtos.kt
 * already groups by flow on the same reasoning.
 */

/**
 * Wire form of [PermanentSelectionAction] (CR 609.4) — what an untargeted mid-resolution selection does
 * to each chosen permanent: untap it (CR 701.21b, Snap) or return it to its owner's hand (CR 701.4a,
 * Azorius Chancery). A data-free enum, so an enum on the wire.
 */
@Serializable
enum class PermanentSelectionActionDto { UNTAP, RETURN_TO_OWNERS_HAND }

/** [PermanentSelectionAction] to its wire form. */
fun PermanentSelectionAction.toDto(): PermanentSelectionActionDto =
    when (this) {
        PermanentSelectionAction.UNTAP -> PermanentSelectionActionDto.UNTAP
        PermanentSelectionAction.RETURN_TO_OWNERS_HAND -> PermanentSelectionActionDto.RETURN_TO_OWNERS_HAND
    }

/** [PermanentSelectionActionDto] back to the engine value. */
fun PermanentSelectionActionDto.toDomain(): PermanentSelectionAction =
    when (this) {
        PermanentSelectionActionDto.UNTAP -> PermanentSelectionAction.UNTAP
        PermanentSelectionActionDto.RETURN_TO_OWNERS_HAND -> PermanentSelectionAction.RETURN_TO_OWNERS_HAND
    }

/**
 * Wire form of [PendingPermanentSelection] (CR 609.4) — the deciding seat, the action the chosen
 * permanents receive, and the already-clamped bounds.
 *
 * The options are **not** carried, and do not need to be: they are battlefield permanents, and the seat
 * view already holds the whole battlefield for every seat (CR 400.2). This is the one pending record
 * for which ADR-007 has no work to do.
 */
@Serializable
data class PendingPermanentSelectionDto(
    val decider: Int,
    val action: PermanentSelectionActionDto,
    val minimum: Int,
    val maximum: Int,
)

/** [PendingPermanentSelection] to its wire form. */
fun PendingPermanentSelection.toDto(): PendingPermanentSelectionDto =
    PendingPermanentSelectionDto(decider.seat, action.toDto(), minimum, maximum)

/** [PendingPermanentSelectionDto] back to the engine value. */
fun PendingPermanentSelectionDto.toDomain(): PendingPermanentSelection =
    PendingPermanentSelection(PlayerId(decider), action.toDomain(), minimum, maximum)
