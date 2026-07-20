package dev.mtgplay.core.zone

import dev.mtgplay.core.identity.PlayerId

/**
 * The identity of one of the game's zones (CR 400.1): which zone, and — for the per-player
 * zones — whose.
 *
 * Per CR 400.2 each player has their own library, hand, and graveyard, while the battlefield,
 * the stack, and exile are shared by all players. The command zone (CR 408) is deliberately not
 * modeled: nothing in the MVP pool ever uses it, and an unrepresentable zone cannot be silently
 * misused — it is added when a card needs it.
 *
 * Rules ordering semantics, for reference: the library (CR 401), graveyard (CR 404), and stack
 * (CR 405) are ordered; the hand (CR 402) and battlefield (CR 403) have no rules-relevant
 * order. The engine nonetheless keeps *every* zone's contents in deterministic,
 * insertion-stable order — see [dev.mtgplay.core.state.GameState].
 */
sealed interface ZoneId {
    /** [owner]'s library (CR 401): the ordered, face-down pile they draw from. */
    data class Library(
        val owner: PlayerId,
    ) : ZoneId

    /** [owner]'s hand (CR 402): hidden from other players (per-seat filtering, ADR-007). */
    data class Hand(
        val owner: PlayerId,
    ) : ZoneId

    /**
     * [owner]'s graveyard (CR 404): face-up and ordered; several MVP abilities function from
     * here (CR 113.6).
     */
    data class Graveyard(
        val owner: PlayerId,
    ) : ZoneId

    /** The shared battlefield (CR 403), where permanents exist. */
    data object Battlefield : ZoneId

    /** The shared stack (CR 405), where spells and abilities wait to resolve, last in first out. */
    data object Stack : ZoneId

    /** The shared exile zone (CR 406); the MVP's madness and plot cards wait here mid-mechanic. */
    data object Exile : ZoneId
}
