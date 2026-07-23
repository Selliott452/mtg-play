package dev.mtgplay.core.state

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId

/**
 * A discard event with two or more applicable replacement effects, awaiting the affected player's
 * CR 616.1 choice of which to apply first. Additive, flagged core (P5.2).
 *
 * When a single card being discarded (CR 701.8) carries two or more replacements that would each
 * modify the discard (CR 614), the affected player chooses one to apply (CR 616.1); the chosen one is
 * applied, and the event is re-evaluated for any that still apply (CR 614.5 — each applies at most
 * once per event). No real MVP card pair produces this — it is exercised by a fixture with two
 * discard→exile replacements — but the framework builds the ordering honestly. Recorded in the state
 * so the pending decision (which replacement) is a pure function of the state (ADR-004); the card is
 * **still in [player]'s hand**, so the applicable replacements are re-derived from its definition.
 *
 * @property player the affected player, whose card is being discarded and who chooses the order
 *   (CR 616.1); the deciding seat of the choice.
 * @property objectId the hand object being discarded, still in [player]'s hand.
 */
data class PendingReplacement(
    val player: PlayerId,
    val objectId: ObjectId,
)
