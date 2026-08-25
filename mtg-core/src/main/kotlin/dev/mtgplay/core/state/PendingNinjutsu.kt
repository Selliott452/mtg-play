package dev.mtgplay.core.state

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId

/**
 * A ninjutsu ability gathering its mana payment (CR 702.49a, CR 602.2b), or absent. Additive, flagged
 * core (`FW-NINJUTSU`). The exact counterpart of [PendingPlot]: the activator has chosen *which* card to
 * ninjutsu and *which* unblocked attacker to return — both enumerated as one
 * `PriorityOption.ActivateNinjutsu` (ADR-005), so neither is a decision this record is still waiting on
 * — and the engine has paused for the payment plan.
 *
 * Nothing has moved while this is open. CR 602.2b pays the cost *after* the ability is put on the stack,
 * but the engine gathers the payment first and performs the whole activation as one transition, exactly
 * as the cast pipeline gathers before executing: the observable order is unchanged, because no player
 * receives priority between the two.
 *
 * @property activator the player activating the ninjutsu ability — the ninja's owner, who controls both
 *   the ability and the returned attacker (CR 702.49a: "an unblocked attacking creature **you**
 *   control").
 * @property ninjaObjectId the card in [activator]'s hand whose ninjutsu ability is being activated; it
 *   stays in that hand until the activation executes, which [GameState] checks.
 * @property returnedAttacker the unblocked attacker (CR 509.1h) that the cost returns to its owner's
 *   hand. Chosen at enumeration, so the pause never has to re-derive it — and it is recorded rather
 *   than re-derived because the ninja enters attacking **the player that creature was attacking**
 *   (CR 702.49d), which is unreadable once the attacker has left combat.
 */
data class PendingNinjutsu(
    val activator: PlayerId,
    val ninjaObjectId: ObjectId,
    val returnedAttacker: ObjectId,
)
