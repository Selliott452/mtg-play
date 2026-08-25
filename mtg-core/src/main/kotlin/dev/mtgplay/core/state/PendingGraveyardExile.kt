package dev.mtgplay.core.state

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId

/**
 * A "target player exiles a card from their graveyard" clause paused for that player's choice
 * (CR 701.3a, CR 404) — Relic of Progenitus' first ability. Additive, flagged core (`W8-D`). Non-null
 * only at that mid-resolution pause, with the resolving ability still on top of the stack.
 *
 * **[decider] is the *targeted* player**, not the ability's controller (CR 701.3a): "target player
 * exiles" makes that player perform the action, and the player who performs an action makes its
 * choices. It may perfectly well *be* the controller — pointing Relic of Progenitus at one's own
 * graveyard is a real line — which is what distinguishes this from [PendingOpponentDiscard], whose
 * decider is an opponent by construction.
 *
 * The option list needs no ADR-007 filtering: a graveyard is a public zone (CR 400.2), so nothing about
 * offering it discloses anything the other seat could not already read.
 *
 * @property decider the targeted player, who chooses which of their own graveyard cards to exile.
 * @property sourceCard the printed identity of the ability's source, for display (CR 113.7c) — the
 *   Relic is still on the battlefield here, since its first ability's cost is only `{T}`.
 */
data class PendingGraveyardExile(
    val decider: PlayerId,
    val sourceCard: CardRef,
)
