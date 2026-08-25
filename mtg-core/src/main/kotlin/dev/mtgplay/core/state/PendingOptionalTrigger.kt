package dev.mtgplay.core.state

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId

/**
 * A resolving triggered ability whose whole effect is inside a printed "**you may**" (CR 603.2,
 * CR 601.3b), paused for its controller's yes/no — Mortuary Mire's "When this land enters, you may put
 * target creature card from your graveyard on top of your library". Additive, flagged core (`W8-A`).
 *
 * The ability is still on top of the stack (CR 608.1): its targets were chosen when it was put there
 * (CR 603.3d), the CR 608.2b re-check has already passed, and only the "may" is outstanding. Whichever
 * way it is answered, the ability then ceases to exist (CR 113.7a).
 *
 * **The pause is at resolution, and that is the whole reason the record exists.** "You may" is an
 * instruction inside the effect, so it is decided a full priority round after the target was chosen and
 * with whatever information that round produced — an opponent who exiled the graveyard in response has
 * changed the answer. Folding it into the target choice ("up to one target") would move the decision
 * earlier and delete that; the engine does not collapse decisions (ADR-004, ADR-005).
 *
 * @property decider the player answering — the ability's controller (CR 603.3d).
 * @property sourceId the ability's source as last known (CR 113.7c), carried so the yes/no can name what
 *   is offering it; the source may have left the battlefield since the ability fired.
 * @property sourceCard the printed identity behind [sourceId].
 */
data class PendingOptionalTrigger(
    val decider: PlayerId,
    val sourceId: ObjectId,
    val sourceCard: CardRef,
)
