package dev.mtgplay.core.state

import dev.mtgplay.core.definition.OptionalCostMode
import dev.mtgplay.core.identity.PlayerId

/**
 * An optional "you may [discard a card | sacrifice a land]; if you do, draw N" clause the engine is resolving
 * as part of a spell (CR 601.3b) — Highway Robbery. Additive, flagged core (P6.2c). The resolving spell is
 * still the top object of the stack (its declaration carries the draw count and the offered modes); the
 * engine has paused for the [decider]'s mode choice ([chosenMode] is `null`) or, having chosen a mode, for
 * their cost-object selection ([chosenMode] set). On the selection the engine pays the mode's cost (a discard
 * through the CR 614/616 framework, or a land sacrifice) and draws, then the spell leaves the stack. Non-null
 * only at one of those two pauses.
 *
 * @property decider the player who may pay a cost and draw — the resolving spell's controller (CR 608.1).
 * @property chosenMode the cost mode chosen, or `null` while the mode choice is pending.
 */
data class PendingOptionalCostDraw(
    val decider: PlayerId,
    val chosenMode: OptionalCostMode? = null,
)
