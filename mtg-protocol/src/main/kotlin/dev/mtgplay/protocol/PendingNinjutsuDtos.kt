package dev.mtgplay.protocol

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.PendingNinjutsu
import dev.mtgplay.core.state.PendingOptionalDraw
import dev.mtgplay.core.state.PendingOptionalTrigger
import kotlinx.serialization.Serializable

/**
 * Wire form of [PendingNinjutsu] (CR 702.49a) — a ninjutsu ability gathering its mana payment.
 *
 * Object ids only, exactly like [PendingPlotDto]: the ninja's *identity* is not published here, because
 * CR 702.49a's "Reveal this card from your hand" is part of the **cost**, which is paid when the
 * activation executes and not while it is still being gathered. A seat learns the card from the
 * [dev.mtgplay.core.event.GameEvent.NinjutsuActivated] narration and from the resulting stack entry, both
 * of which come after the reveal has actually happened.
 */
@Serializable
data class PendingNinjutsuDto(
    val activator: Int,
    val ninjaObjectId: Long,
    val returnedAttacker: Long,
)

/** [PendingNinjutsu] to its wire form. */
fun PendingNinjutsu.toDto(): PendingNinjutsuDto =
    PendingNinjutsuDto(activator.seat, ninjaObjectId.value, returnedAttacker.value)

/** [PendingNinjutsuDto] back to the engine value. */
fun PendingNinjutsuDto.toDomain(): PendingNinjutsu =
    PendingNinjutsu(PlayerId(activator), ObjectId(ninjaObjectId), ObjectId(returnedAttacker))

/**
 * Wire form of [PendingOptionalDraw] (CR 601.3b) — a bare "you may draw N" clause awaiting its yes/no.
 * The source it names is a battlefield permanent's last-known information (CR 113.7c), public to both
 * seats, so nothing here is redacted.
 */
@Serializable
data class PendingOptionalDrawDto(
    val decider: Int,
    val drawCount: Int,
    val sourceId: Long,
    val sourceCard: String,
)

/** [PendingOptionalDraw] to its wire form. */
fun PendingOptionalDraw.toDto(): PendingOptionalDrawDto =
    PendingOptionalDrawDto(decider.seat, drawCount, sourceId.value, sourceCard.name)

/** [PendingOptionalDrawDto] back to the engine value. */
fun PendingOptionalDrawDto.toDomain(): PendingOptionalDraw =
    PendingOptionalDraw(PlayerId(decider), drawCount, ObjectId(sourceId), CardRef(sourceCard))

/**
 * Wire form of [PendingOptionalTrigger] (CR 603.2) — a resolving triggered ability whose whole effect is
 * inside a printed "you may", awaiting its controller's yes/no. Like [PendingOptionalDrawDto] it names a
 * battlefield source as last known (CR 113.7c) and redacts nothing.
 */
@Serializable
data class PendingOptionalTriggerDto(
    val decider: Int,
    val sourceId: Long,
    val sourceCard: String,
)

/** [PendingOptionalTrigger] to its wire form. */
fun PendingOptionalTrigger.toDto(): PendingOptionalTriggerDto =
    PendingOptionalTriggerDto(decider.seat, sourceId.value, sourceCard.name)

/** [PendingOptionalTriggerDto] back to the engine value. */
fun PendingOptionalTriggerDto.toDomain(): PendingOptionalTrigger =
    PendingOptionalTrigger(PlayerId(decider), ObjectId(sourceId), CardRef(sourceCard))
