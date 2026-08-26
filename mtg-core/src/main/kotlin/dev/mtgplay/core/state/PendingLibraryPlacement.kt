package dev.mtgplay.core.state

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId

/**
 * An "the **owner** of target nonland permanent puts it into their library second from the top or on the
 * bottom" clause paused for that owner's choice (CR 401.1, CR 108.3) — Deem Inferior's whole effect.
 * Additive, flagged core (`W9-F`). Non-null only at that mid-resolution pause, with the resolving spell
 * still on top of the stack and [permanent] still on the battlefield.
 *
 * **[decider] is the permanent's owner**, which is neither the resolving spell's controller (normally
 * its opponent) nor, in general, the permanent's controller: CR 108.3 fixes ownership for the game while
 * control can change hands. The printed line says "the owner", and the player who performs an action
 * makes its choices — the same rule that puts Relic of Progenitus' exile on the *targeted* player.
 *
 * **The permanent is recorded rather than re-derived from the spell's target**, because the two questions
 * differ once the pause is open: the target was validated at CR 608.2b and this record is what the apply
 * step moves. Nothing may move it while the pause is open — the resolution is one transition — so the
 * recorded id and the target still name the same object, and the apply step fails loudly if they do not.
 *
 * Nothing here is hidden: the permanent, its owner, and the two positions are all public (CR 400.2). The
 * *result* is a card at a known depth in a hidden zone, which the seat views already handle by not
 * showing library contents to anybody.
 *
 * @property decider the permanent's owner, who chooses the depth.
 * @property permanent the battlefield object that will be put into [decider]'s library.
 * @property sourceCard the printed identity of the spell that issued the instruction, for display.
 */
data class PendingLibraryPlacement(
    val decider: PlayerId,
    val permanent: ObjectId,
    val sourceCard: CardRef,
)
