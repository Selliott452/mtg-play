package dev.mtgplay.core.state

import dev.mtgplay.core.identity.PlayerId

/**
 * An "as this permanent enters, choose a colour" choice the engine is gathering mid-resolution
 * (CR 614.12) — Utopia Sprawl. Additive, flagged core (P6.2a). The permanent spell is resolving and is
 * still the top object of the stack; the engine has paused for the [decider]'s colour choice before the
 * object enters the battlefield, and completes the entry (storing the colour on the entering object)
 * once the choice arrives. Non-null only at that mid-resolution pause, where no player holds priority
 * and the resolving spell is on top of the stack.
 *
 * @property decider the player making the choice — the resolving permanent's controller (CR 614.12).
 */
data class PendingColorChoice(
    val decider: PlayerId,
)
