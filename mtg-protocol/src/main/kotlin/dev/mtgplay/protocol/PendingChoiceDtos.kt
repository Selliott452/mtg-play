package dev.mtgplay.protocol

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.PendingMadness
import dev.mtgplay.core.state.PendingMulligan
import dev.mtgplay.core.state.PendingReplacement
import kotlinx.serialization.Serializable

/*
 * Wire mirrors of the pre-cast/pre-game choice pending nouns a [SeatView] carries unfiltered: none
 * carries hand contents (the mulligan bottoming and discard selections are the deciding seat's
 * private request options, not stored here).
 */

/** Wire form of [PendingMadness] (CR 702.35b). */
@Serializable
data class PendingMadnessDto(
    val owner: Int,
    val exiledObjectId: Long,
)

/** [PendingMadness] to its wire form. */
fun PendingMadness.toDto(): PendingMadnessDto = PendingMadnessDto(owner.seat, exiledObjectId.value)

/** [PendingMadnessDto] back to the engine value. */
fun PendingMadnessDto.toDomain(): PendingMadness = PendingMadness(PlayerId(owner), ObjectId(exiledObjectId))

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
