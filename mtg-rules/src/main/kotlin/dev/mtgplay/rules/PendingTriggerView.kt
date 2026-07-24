package dev.mtgplay.rules

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId

/**
 * A fired-but-unplaced triggered ability as any seat may see it (ADR-007): the public last-known
 * information of a [dev.mtgplay.core.state.PendingTrigger], with its captured
 * [dev.mtgplay.core.definition.TriggeredAbility] definition dropped (static card data referenced by
 * [CardRef], as on [StackEntryView]).
 *
 * Pending triggers are public — they are the last-known information of public game events (CR 603.3,
 * CR 603.10). A seat sees which abilities have fired, from which sources, under whose control, and
 * the trigger's numeric/linked information, all of which concern open events.
 *
 * @property sourceId the source object's last-known id (CR 603.10).
 * @property sourceCard the source's printed identity.
 * @property controller the player who controls the trigger and orders it among simultaneous ones
 *   (CR 603.3b/d).
 * @property amount the trigger's numeric linked information (CR 118.9); `0` when it carries none.
 * @property subject the specific object the effect acts on (CR 603.10), or `null` when none.
 */
data class PendingTriggerView(
    val sourceId: ObjectId,
    val sourceCard: CardRef,
    val controller: PlayerId,
    val amount: Int,
    val subject: ObjectId?,
)
