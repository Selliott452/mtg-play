package dev.mtgplay.core.state

import kotlinx.collections.immutable.PersistentList

/**
 * Everything the game tracks per player: the life total and the three per-player zones
 * (CR 400.2).
 *
 * Zone ordering conventions, fixed here for the whole engine:
 * - [library] (CR 401) — index 0 is the top of the library, the next card drawn.
 * - [hand] (CR 402) — no rules-relevant order; kept insertion-stable for determinism (see the
 *   iteration rule on [GameState]). Hidden from other seats (ADR-007).
 * - [graveyard] (CR 404) — ordered; the *last* element is the top, the most recently placed.
 *
 * [life] starts at 20 in the MVP format (CR 119.1) but is deliberately unconstrained here:
 * life totals legally go negative in play — e.g. damage taking a player below zero before the
 * state-based action ends the game (CR 704.5a).
 *
 * The last three properties are engine-maintained bookkeeping added in P1.2 (their transitions
 * live in `mtg-rules`): the player's standing in the current priority round, the CR 704.5c
 * draw-attempt fact, and the answered-decision count that anchors stable decision-request
 * identities (ADR-004, ADR-006).
 *
 * @property life the player's current life total (CR 119.1); may be negative.
 * @property library the player's library (CR 401); index 0 is the top.
 * @property hand the player's hand (CR 402).
 * @property graveyard the player's graveyard (CR 404); the last element is the top.
 * @property priorityStatus where this player stands in the current priority round (CR 117);
 *   [PriorityStatus.NONE] whenever no round is open.
 * @property attemptedDrawFromEmptyLibrary whether this player has attempted to draw a card from
 *   an empty library since state-based actions were last checked. Recorded as an explicit fact
 *   by the failed draw itself so the CR 704.5c state-based action acts on the *attempt*, never
 *   inferring from library emptiness.
 * @property decisionsAnswered how many decisions this seat has answered so far (ADR-004). With
 *   the seat it forms the stable identity of the seat's next decision request, which is what
 *   makes a recorded decision log unambiguous on replay (ADR-006).
 */
data class PlayerState(
    val life: Int,
    val library: PersistentList<GameObject>,
    val hand: PersistentList<GameObject>,
    val graveyard: PersistentList<GameObject>,
    val priorityStatus: PriorityStatus = PriorityStatus.NONE,
    val attemptedDrawFromEmptyLibrary: Boolean = false,
    val decisionsAnswered: Int = 0,
) {
    init {
        require(decisionsAnswered >= 0) { "answered-decision count must be non-negative, was $decisionsAnswered" }
    }
}
