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
 * @property landsEnteredThisTurn how many lands have entered the battlefield under this player's control
 *   during the current turn (CR 305, CR 603.6a). Additive, flagged core (`W9-C`) — the per-turn fact
 *   **landfall** reads (Searing Blaze's "if you had a land enter the battlefield under your control this
 *   turn"). Non-negative, and reset to 0 for every player when a turn begins, exactly as [drawsThisTurn]
 *   is and for the same reason: "this turn" is a per-turn window.
 *
 *   **Per player, and counting *entries* rather than *plays*.** [Turn.landsPlayedThisTurn] is the
 *   neighbouring counter and is neither: it is on the turn because only the active player may *play* a
 *   land (CR 305.1), and it counts the CR 305.1 land drop. Landfall is a different question — a land put
 *   onto the battlefield by a search, a return, or a blink triggers it and consumes no land drop — and any
 *   player may have a land enter on any turn, which is why this one is per seat. Encoding landfall against
 *   the land-drop counter would be a plausible-looking wrong card in a gauntlet holding fetch effects.
 *
 *   **A count, not a flag**, matching [drawsThisTurn]: the pool's landfall card only asks "was there at
 *   least one?", but nothing is gained by throwing away the number and a "second landfall this turn"
 *   card would need it. Incremented at the single battlefield-entry announcement site, so a new entry
 *   path cannot forget it.
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
 * @property energyCounters how many `{E}` energy counters this player has (CR 122.1, CR 107.16).
 *   Additive, flagged core (`FW-EQUIP`) — Inventor's Axe's "you get `{E}{E}`" and "Equip—Pay `{E}{E}`".
 *
 *   **The first counter this engine puts on a *player* rather than on an object**, which is why it is a
 *   field here rather than a reuse of [dev.mtgplay.core.state.Counter]: that type is a multiset key on a
 *   [GameObject], and CR 122.1 is explicit that energy counters are counters a *player* has. Modelled as
 *   a plain [Int] rather than a map because the pool prints exactly one kind of player counter; poison
 *   and experience would each be a sibling field, not a widening, for the same reason
 *   [dev.mtgplay.core.state.GameObject.manaAbilitiesActivatedThisTurn] and its sibling stayed apart.
 *
 *   **Not turn-scoped and never reset.** Energy is spent, not expired (CR 107.16): a player who gets
 *   `{E}{E}` on turn three still has it on turn nine if nothing paid it. That makes it unlike every other
 *   per-player tally on this type — [drawsThisTurn] is cleared each turn and [combatPhasesToSkip] is
 *   consumed by an event — and it is the whole reason the Axe's equip cost is repeatable but finite.
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
    val landsEnteredThisTurn: Int = 0,
    val combatPhasesToSkip: Int = 0,
    val energyCounters: Int = 0,
) {
    init {
        require(decisionsAnswered >= 0) { "answered-decision count must be non-negative, was $decisionsAnswered" }
        require(drawsThisTurn >= 0) { "CR 121.1: draws this turn must be non-negative, was $drawsThisTurn" }
        require(landsEnteredThisTurn >= 0) {
            "CR 305: lands entered this turn must be non-negative, was $landsEnteredThisTurn"
        }
        require(energyCounters >= 0) {
            "CR 122.1: a player's energy-counter total is non-negative — a cost that cannot be paid is " +
                "never enumerated (ADR-005), so it can never overdraw; was $energyCounters"
        }
        require(combatPhasesToSkip >= 0) {
            "CR 500.10: scheduled combat-phase skips must be non-negative, was $combatPhasesToSkip"
        }
    }
}
