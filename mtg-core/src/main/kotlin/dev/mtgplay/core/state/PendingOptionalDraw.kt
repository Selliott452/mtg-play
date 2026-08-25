package dev.mtgplay.core.state

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId

/**
 * A bare optional "you may draw N" clause the engine is resolving (CR 601.3b) — Ninja of the Deep Hours'
 * combat-damage trigger. Additive, flagged core (`FW-OPTDRAW`). The clause's ability has already left the
 * stack (CR 113.7a) and the engine has paused for the [decider]'s yes/no; there is no second pause,
 * because there is no cost to select an object for.
 *
 * @property decider the player who may draw — the resolving object's controller (CR 603.3d).
 * @property drawCount how many cards an acceptance draws.
 * @property sourceId the resolving object's source as last known (CR 113.7c), carried so the yes/no can
 *   name what is offering it; the source may have left the battlefield since the ability fired.
 * @property sourceCard the printed identity behind [sourceId].
 */
data class PendingOptionalDraw(
    val decider: PlayerId,
    val drawCount: Int,
    val sourceId: ObjectId,
    val sourceCard: CardRef,
)
