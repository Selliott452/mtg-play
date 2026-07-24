package dev.mtgplay.protocol

import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.PriorityStatus
import kotlinx.serialization.Serializable

/*
 * Wire mirrors of the mana/priority primitive enums. Every mapping is an exhaustive `when` (no
 * `else`), so a new engine member breaks compilation here in both directions.
 */

/** Wire form of [ManaType] (CR 106.1b). */
@Serializable
enum class ManaTypeDto { WHITE, BLUE, BLACK, RED, GREEN, COLORLESS }

/** Wire form of [Color] (CR 105.1). */
@Serializable
enum class ColorDto { WHITE, BLUE, BLACK, RED, GREEN }

/** Wire form of [PriorityStatus] (CR 117). */
@Serializable
enum class PriorityStatusDto { NONE, HOLDS_PRIORITY, HAS_PASSED }

/** [ManaType] to its wire form. */
fun ManaType.toDto(): ManaTypeDto =
    when (this) {
        ManaType.WHITE -> ManaTypeDto.WHITE
        ManaType.BLUE -> ManaTypeDto.BLUE
        ManaType.BLACK -> ManaTypeDto.BLACK
        ManaType.RED -> ManaTypeDto.RED
        ManaType.GREEN -> ManaTypeDto.GREEN
        ManaType.COLORLESS -> ManaTypeDto.COLORLESS
    }

/** [ManaTypeDto] back to the engine value. */
fun ManaTypeDto.toDomain(): ManaType =
    when (this) {
        ManaTypeDto.WHITE -> ManaType.WHITE
        ManaTypeDto.BLUE -> ManaType.BLUE
        ManaTypeDto.BLACK -> ManaType.BLACK
        ManaTypeDto.RED -> ManaType.RED
        ManaTypeDto.GREEN -> ManaType.GREEN
        ManaTypeDto.COLORLESS -> ManaType.COLORLESS
    }

/** [Color] to its wire form. */
fun Color.toDto(): ColorDto =
    when (this) {
        Color.WHITE -> ColorDto.WHITE
        Color.BLUE -> ColorDto.BLUE
        Color.BLACK -> ColorDto.BLACK
        Color.RED -> ColorDto.RED
        Color.GREEN -> ColorDto.GREEN
    }

/** [ColorDto] back to the engine value. */
fun ColorDto.toDomain(): Color =
    when (this) {
        ColorDto.WHITE -> Color.WHITE
        ColorDto.BLUE -> Color.BLUE
        ColorDto.BLACK -> Color.BLACK
        ColorDto.RED -> Color.RED
        ColorDto.GREEN -> Color.GREEN
    }

/** [PriorityStatus] to its wire form. */
fun PriorityStatus.toDto(): PriorityStatusDto =
    when (this) {
        PriorityStatus.NONE -> PriorityStatusDto.NONE
        PriorityStatus.HOLDS_PRIORITY -> PriorityStatusDto.HOLDS_PRIORITY
        PriorityStatus.HAS_PASSED -> PriorityStatusDto.HAS_PASSED
    }

/** [PriorityStatusDto] back to the engine value. */
fun PriorityStatusDto.toDomain(): PriorityStatus =
    when (this) {
        PriorityStatusDto.NONE -> PriorityStatus.NONE
        PriorityStatusDto.HOLDS_PRIORITY -> PriorityStatus.HOLDS_PRIORITY
        PriorityStatusDto.HAS_PASSED -> PriorityStatus.HAS_PASSED
    }
