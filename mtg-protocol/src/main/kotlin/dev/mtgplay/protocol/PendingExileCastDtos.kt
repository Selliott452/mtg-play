package dev.mtgplay.protocol

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.PendingCascade
import dev.mtgplay.core.state.PendingMadness
import dev.mtgplay.core.state.PendingRebound
import kotlinx.collections.immutable.toPersistentList
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

/**
 * Wire form of [PendingCascade] (CR 702.85a) — a resolving cascade ability that has finished exiling and
 * is awaiting either its controller's free-cast yes/no or the random bottoming that ends it. Added by
 * `W9-G`.
 *
 * **The third member of this file and the one that is not the same two fields**, because cascade's
 * record outlives its own decision: [candidateObjectId] is `null` once the yes/no is answered, while
 * [exiledObjectIds] has to survive the nested free cast so the bottoming still knows what to put back.
 * A peer reading a `null` candidate beside a non-empty exile list is looking at a cascade whose cast is
 * in progress, not at a malformed record.
 *
 * Fully public for the reason madness and rebound are, and one more: cascade exiles **face up**, so both
 * seats have already seen every card named here in the seat view's exile list. The order the unchosen
 * cards return in is never on the wire at all — it is drawn from the match PRNG as the ability finishes.
 */
@Serializable
data class PendingCascadeDto(
    val controller: Int,
    val exiledObjectIds: List<Long>,
    val candidateObjectId: Long?,
)

/** [PendingCascade] to its wire form. */
fun PendingCascade.toDto(): PendingCascadeDto =
    PendingCascadeDto(controller.seat, exiledObjectIds.map { it.value }, candidateObjectId?.value)

/** [PendingCascadeDto] back to the engine value. */
fun PendingCascadeDto.toDomain(): PendingCascade =
    PendingCascade(
        PlayerId(controller),
        exiledObjectIds.map(::ObjectId).toPersistentList(),
        candidateObjectId?.let(::ObjectId),
    )
