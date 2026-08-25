package dev.mtgplay.core.state

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId

/**
 * An "as this permanent enters, choose a colour" choice the engine is gathering as the permanent enters
 * (CR 614.12) — Utopia Sprawl, and the Gate cycle. Additive, flagged core (P6.2a); widened by `W8-A`.
 *
 * The engine has paused for the [decider]'s colour choice **before** the object joins the battlefield,
 * and completes the entry — storing the colour on the entering object — once the choice arrives. Non-null
 * only at that pause, where no player holds priority.
 *
 * **Two routes reach the battlefield, so this record names which one it interrupted.** A permanent
 * *spell* is resolving and is still the top object of the stack (Utopia Sprawl), and [playedLand] is
 * `null`; a *land* is never cast (CR 305.1, CR 116.2a) and is instead mid-way through the play-land
 * special action, with the card still in the [decider]'s hand and [playedLand] naming it. The choice
 * itself is identical — CR 614.12 knows nothing about how the permanent got there — but the resume does
 * not, so the route is recorded rather than inferred from whatever happens to be on the stack.
 *
 * @property decider the player making the choice — the entering permanent's controller (CR 614.12).
 * @property playedLand the hand card object of a land whose play-land special action this choice
 *   interrupted (CR 305.1), or `null` when a resolving permanent spell on top of the stack is entering.
 */
data class PendingColorChoice(
    val decider: PlayerId,
    val playedLand: ObjectId? = null,
)
