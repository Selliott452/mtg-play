package dev.mtgplay.core.state

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import kotlinx.collections.immutable.PersistentList

/**
 * A "reveal top N, put one into hand, rest into graveyard" selection the engine is gathering
 * mid-resolution (CR 701.16) — Malevolent Rumble. Additive, flagged core (P6.2a). The resolving spell
 * is still the top object of the stack and the [revealedIds] are still the top of the [decider]'s
 * library (revealed but not yet moved); the engine has paused for the choice of which matching card (if
 * any) to keep, and on resume moves the chosen card to hand and the rest to the graveyard before the
 * spell leaves the stack. Non-null only at that mid-resolution pause.
 *
 * @property decider the resolving spell's controller, whose library was revealed and who chooses.
 * @property revealedIds the revealed top-of-library object ids, in library (top-first) order.
 */
data class PendingRevealSelection(
    val decider: PlayerId,
    val revealedIds: PersistentList<ObjectId>,
)
