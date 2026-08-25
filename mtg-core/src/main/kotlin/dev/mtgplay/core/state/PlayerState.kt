package dev.mtgplay.core.state

import dev.mtgplay.core.mana.ManaType
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/**
 * Everything the game tracks per player: the life total, the three per-player zones (CR 400.2),
 * and the mana pool (CR 106.4).
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
 * @property manaPool the mana in this player's pool (CR 106.4), a multiset of [ManaType] kept
 *   in insertion order (production order; no rules meaning, deterministic per the [GameState]
 *   iteration rule). Filled by resolving mana abilities (CR 605.3), drained by payment
 *   (CR 601.2g–h), and emptied when each step and phase ends (CR 500.4).
 * @property priorityStatus where this player stands in the current priority round (CR 117);
 *   [PriorityStatus.NONE] whenever no round is open.
 * @property attemptedDrawFromEmptyLibrary whether this player has attempted to draw a card from
 *   an empty library since state-based actions were last checked. Recorded as an explicit fact
 *   by the failed draw itself so the CR 704.5c state-based action acts on the *attempt*, never
 *   inferring from library emptiness.
 * @property decisionsAnswered how many decisions this seat has answered so far (ADR-004). With
 *   the seat it forms the stable identity of the seat's next decision request, which is what
 *   makes a recorded decision log unambiguous on replay (ADR-006).
 * @property drawsThisTurn how many cards this player has drawn in the current turn (CR 121.1 /
 *   CR 500). Additive, flagged core (P6.2a): the count "in a turn" a per-turn draw trigger watches —
 *   Sneaky Snacker's "when you draw your third card in a turn" (CR 603.2). Incremented by each
 *   successful draw and reset to 0 for every player when a turn begins (`mtg-rules`); a per-player
 *   counter because any player may draw on any turn (an opponent's-turn draw effect still counts
 *   toward *that* player's per-turn total). Non-negative.
 * @property combatPhasesToSkip how many of this player's **next** combat phases are skipped (CR 500.10),
 *   Stonehorn Dignitary's "target opponent skips their next combat phase". Additive, flagged core
 *   (`W8-G`). Non-negative.
 *
 *   **A count, not a flag**, and that is the one place it deliberately parts company with
 *   [GameObject.skipsNextUntapStep], the marker it otherwise mirrors: two Dignitaries — or one blinked
 *   twice, which is the line UWX Familiar actually plays — must cost their victim two combat phases, so
 *   a second application has to add rather than be absorbed. A permanent's untap marker is a fact about
 *   a permanent ("it doesn't untap"); this is a queue of scheduled skips.
 *
 *   **It is not reset when a turn begins**, unlike [drawsThisTurn]. "Their next combat phase" is a
 *   standing obligation with no deadline: it is set during somebody else's turn and spent whenever this
 *   player's next combat phase would have begun, however many turns later. `mtg-rules` decrements it at
 *   exactly one point, the moment a combat phase is actually skipped.
 *
 *   Per-player rather than on [Turn] — the split [Turn.landsPlayedThisTurn] sits on the other side of —
 *   because the fact is written about a *non-active* player and must outlive the turn it was written on.
 */
data class PlayerState(
    val life: Int,
    val library: PersistentList<GameObject>,
    val hand: PersistentList<GameObject>,
    val graveyard: PersistentList<GameObject>,
    val manaPool: PersistentList<ManaType> = persistentListOf(),
    val priorityStatus: PriorityStatus = PriorityStatus.NONE,
    val attemptedDrawFromEmptyLibrary: Boolean = false,
    val decisionsAnswered: Int = 0,
    val drawsThisTurn: Int = 0,
    val combatPhasesToSkip: Int = 0,
) {
    init {
        require(decisionsAnswered >= 0) { "answered-decision count must be non-negative, was $decisionsAnswered" }
        require(drawsThisTurn >= 0) { "CR 121.1: draws this turn must be non-negative, was $drawsThisTurn" }
        require(combatPhasesToSkip >= 0) {
            "CR 500.10: scheduled combat-phase skips must be non-negative, was $combatPhasesToSkip"
        }
    }
}
