package dev.mtgplay.core.state

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import kotlinx.collections.immutable.PersistentList

/**
 * A cast the engine is gathering decisions for: the caster has chosen to cast a hand card
 * (CR 601.2), and the choices the cast needs — targets (CR 601.2c), then a payment plan
 * (CR 601.2g) — are being collected one `DecisionRequest` at a time (ADR-004).
 *
 * While a [PendingCast] is open, the card is **still in the caster's hand** and nothing about
 * the game has changed: the engine runs the whole CR 601 pipeline atomically in the single
 * transition that receives the final choice, so an abandoned or failed cast leaves exactly the
 * pre-cast state — the CR 601.3e/CR 728 rewind is the immutability of the paused state itself
 * (see the casting pipeline in `mtg-rules`). This record is only the gathered-so-far choices,
 * which is what lets the pending decision request stay a pure function of the state (ADR-004
 * resumability).
 *
 * @property caster the player casting; they hold priority for the whole gathering.
 * @property cardObjectId the hand object being cast; still in [caster]'s hand.
 * @property chosenTargets the targets chosen so far: `null` before the targets decision
 *   (CR 601.2c) is answered, the chosen list after — empty exactly when the spell targets
 *   nothing.
 */
data class PendingCast(
    val caster: PlayerId,
    val cardObjectId: ObjectId,
    val chosenTargets: PersistentList<Target>?,
)
