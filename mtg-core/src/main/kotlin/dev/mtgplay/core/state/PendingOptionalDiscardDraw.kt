package dev.mtgplay.core.state

import dev.mtgplay.core.identity.PlayerId

/**
 * An optional "you may discard a card; if you do, draw N" clause the engine is resolving (CR 601.3b) —
 * Melded Moxite's enters-the-battlefield clause. Additive, flagged core (P6.2a). The clause's ability
 * has already left the stack; the engine has paused for the [decider]'s yes/no ([awaitingDiscard] is
 * `false`) or, having accepted, for their discard selection ([awaitingDiscard] is `true`). On the
 * discard the engine discards the chosen card (through the CR 614/616 framework, so madness intercepts
 * it) and draws [drawCount]. Non-null only at one of those two pauses.
 *
 * @property decider the player who may discard and draw — the ability's controller (CR 603.3d).
 * @property drawCount how many cards to draw on the discard (Melded Moxite's two).
 * @property awaitingDiscard `false` while the yes/no is pending, `true` after acceptance while the
 *   discard selection is pending.
 */
data class PendingOptionalDiscardDraw(
    val decider: PlayerId,
    val drawCount: Int,
    val awaitingDiscard: Boolean,
)
