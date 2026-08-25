package dev.mtgplay.core.state

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId

/**
 * A "you may tap or untap [target]" clause the engine is resolving (CR 608.2c) — Sewer-veillance Cam's
 * enters-or-leaves trigger. Additive, flagged core (`W8-G`). The resolving object's ordinary effect has
 * already run and it is still the top of the stack; the engine has paused for the [decider]'s three-way
 * answer, after which the tap or untap happens and the resolution completes. Non-null only at that pause.
 *
 * **The target is stored, unlike a [PendingPermanentSelection]'s option list**, and the difference is
 * that this one is not re-derivable. A permanent *selection* re-derives its candidates from a filter over
 * the battlefield (ADR-004), but a target was chosen a priority round earlier at CR 603.3d and survived
 * the CR 608.2b re-check; nothing in the paused state names it except the resolving stack entry, and
 * reaching back into the stack to re-read it would make the pause depend on the entry's shape. Storing
 * the id keeps the pause a flat fact.
 *
 * @property decider the resolving object's controller, who makes the choice (CR 608.2c — "you").
 * @property targetId the creature the clause may tap or untap, as chosen at CR 603.3d and re-checked at
 *   CR 608.2b.
 * @property sourceId the resolving object's source as last known (CR 113.7c), carried so the choice can
 *   name what is offering it — and it genuinely may be gone: the Cam's second trigger fires *because*
 *   the artifact left the battlefield.
 * @property sourceCard the printed identity behind [sourceId].
 */
data class PendingTapOrUntap(
    val decider: PlayerId,
    val targetId: ObjectId,
    val sourceId: ObjectId,
    val sourceCard: CardRef,
)
