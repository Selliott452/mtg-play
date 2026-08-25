package dev.mtgplay.protocol

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.PendingMadness
import dev.mtgplay.core.state.PendingRebound
import kotlinx.serialization.Serializable

/*
 * Wire mirrors of the two "a card waits face-up in exile for its controller's free-cast yes/no"
 * pending nouns — madness (CR 702.35b) and rebound (CR 702.88b). Both are fully public and carry the
 * same two fields, because exile is a face-up zone (CR 406.3) and a [SeatView] already names the card
 * itself in its exile list; what the record adds is only whose choice is open and about which object.
 *
 * They stay two records rather than one flagged record for the rules difference the engine states in
 * [PendingRebound]: on "no", madness puts the card into its owner's graveyard while rebound simply
 * leaves it exiled for the rest of the game.
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

/**
 * Wire form of [PendingRebound] (CR 702.88b) — a resolved rebound delayed ability awaiting its
 * controller's free-cast yes/no. Added by `FW-BLINK`.
 */
@Serializable
data class PendingReboundDto(
    val controller: Int,
    val exiledObjectId: Long,
)

/** [PendingRebound] to its wire form. */
fun PendingRebound.toDto(): PendingReboundDto = PendingReboundDto(controller.seat, exiledObjectId.value)

/** [PendingReboundDto] back to the engine value. */
fun PendingReboundDto.toDomain(): PendingRebound = PendingRebound(PlayerId(controller), ObjectId(exiledObjectId))
