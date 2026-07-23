package dev.mtgplay.core.state

import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import kotlinx.collections.immutable.PersistentList

/**
 * An activated ability the engine is gathering costs for (CR 602.2): the activator has chosen to
 * activate an ability and the cost's selections — a card to discard (CR 602.2b), then a payment plan
 * (CR 602.2f–g) — are being collected one `DecisionRequest` at a time (ADR-004). Additive, flagged core
 * (P6.2a).
 *
 * While a [PendingActivation] is open, nothing about the game has changed: the whole activation runs
 * atomically in the transition that receives the final choice, so an aborted activation leaves exactly
 * the pre-activation state (CR 602.2 atomicity). This record is only the gathered-so-far choices, which
 * is what lets the pending decision stay a pure function of the state (ADR-004 resumability).
 *
 * @property activator the player activating; they hold priority for the whole gathering.
 * @property sourceObjectId the object whose ability is being activated; in the [source] zone.
 * @property source the zone the source is in (CR 113.6) — battlefield or hand.
 * @property abilityIndex which of the source definition's activated abilities is being activated.
 * @property chosenDiscard the hand cards chosen to pay a "discard a card" cost component: `null` before
 *   the selection is answered when the cost demands one, an (empty) list once settled or when no such
 *   component applies.
 */
data class PendingActivation(
    val activator: PlayerId,
    val sourceObjectId: ObjectId,
    val source: AbilityZoneScope,
    val abilityIndex: Int,
    val chosenDiscard: PersistentList<ObjectId>?,
)
