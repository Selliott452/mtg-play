package dev.mtgplay.protocol

import dev.mtgplay.core.definition.TapOrUntapChoice
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.PendingTapOrUntap
import kotlinx.serialization.Serializable

/*
 * The wire forms of the "you may tap or untap [target]" clause (`W8-G`, CR 608.2c) — the three-way answer
 * and the pending record that awaits it. Grouped in one file for PermanentSelectionDtos.kt's reason: the
 * record is meaningless without the enum, so a reader asking what the pause offers should not have to
 * cross files to find out.
 */

/**
 * Wire form of [TapOrUntapChoice] (CR 608.2c) — the three answers to a "you may tap or untap" clause. A
 * data-free enum, so an enum on the wire; [TapOrUntapChoiceDto.DECLINE] is first, the engine's
 * convention that index 0 is always the opt-out.
 */
@Serializable
enum class TapOrUntapChoiceDto { DECLINE, TAP, UNTAP }

/** [TapOrUntapChoice] to its wire form. */
fun TapOrUntapChoice.toDto(): TapOrUntapChoiceDto =
    when (this) {
        TapOrUntapChoice.DECLINE -> TapOrUntapChoiceDto.DECLINE
        TapOrUntapChoice.TAP -> TapOrUntapChoiceDto.TAP
        TapOrUntapChoice.UNTAP -> TapOrUntapChoiceDto.UNTAP
    }

/** [TapOrUntapChoiceDto] back to the engine value. */
fun TapOrUntapChoiceDto.toDomain(): TapOrUntapChoice =
    when (this) {
        TapOrUntapChoiceDto.DECLINE -> TapOrUntapChoice.DECLINE
        TapOrUntapChoiceDto.TAP -> TapOrUntapChoice.TAP
        TapOrUntapChoiceDto.UNTAP -> TapOrUntapChoice.UNTAP
    }

/**
 * Wire form of [PendingTapOrUntap] (CR 608.2c) — the deciding seat, the target the clause may act on, and
 * the clause's source as last known (CR 113.7c).
 *
 * Everything here is public: the target was announced when the trigger went on the stack (CR 603.3d) and
 * the three answers are the same three for every board, so — like [PendingPermanentSelectionDto] — this
 * record gives ADR-007 nothing to redact.
 */
@Serializable
data class PendingTapOrUntapDto(
    val decider: Int,
    val targetId: Long,
    val sourceId: Long,
    val sourceCard: String,
)

/** [PendingTapOrUntap] to its wire form. */
fun PendingTapOrUntap.toDto(): PendingTapOrUntapDto =
    PendingTapOrUntapDto(decider.seat, targetId.value, sourceId.value, sourceCard.name)

/** [PendingTapOrUntapDto] back to the engine value. */
fun PendingTapOrUntapDto.toDomain(): PendingTapOrUntap =
    PendingTapOrUntap(PlayerId(decider), ObjectId(targetId), ObjectId(sourceId), CardRef(sourceCard))
