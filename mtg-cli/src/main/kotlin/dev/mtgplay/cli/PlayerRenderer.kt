package dev.mtgplay.cli

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState

/*
 * One player's board, rendered for the viewer (P6.4 deliverable 1). Public information - life,
 * battlefield, graveyard contents, and zone counts - is shown for both seats; the opponent's hand
 * is shown only as a count and libraries only as counts (hidden information, [MatchView]).
 */

/**
 * The lines for one [seat]'s board: life, zone counts, graveyard contents, battlefield permanents,
 * mana pool, and - only when [showHand], i.e. the viewer's own seat - the hand contents.
 */
fun renderPlayer(
    view: MatchView,
    seat: PlayerId,
    showHand: Boolean,
): List<String> {
    val state = view.state
    val player =
        state.players[seat] ?: error("seat ${seat.seat} is not seated in this game")
    val youMarker = if (seat == view.viewer) " (you)" else ""
    return buildList {
        add("${view.nameOf(seat)}$youMarker - Life ${player.life}")
        add("  ${zoneCounts(player, showHand)}")
        graveyardLine(player)?.let { add(it) }
        manaPoolLine(player)?.let { add(it) }
        addAll(battlefieldLines(state, seat))
        if (showHand) addAll(handLines(state, player))
    }
}

/** The hand/library/graveyard counts (CR 401/402/404); the opponent's hand count is public, its cards are not. */
private fun zoneCounts(
    player: PlayerState,
    showHand: Boolean,
): String {
    val handNote = if (showHand) "listed below" else "hidden"
    return "Hand: ${player.hand.size} ($handNote) | " +
        "Library: ${player.library.size} | Graveyard: ${player.graveyard.size}"
}

/** The graveyard contents (CR 404 - a public, ordered zone), or `null` when empty. */
private fun graveyardLine(player: PlayerState): String? {
    if (player.graveyard.isEmpty()) return null
    return "  Graveyard: ${player.graveyard.joinToString(", ") { it.card.name }}"
}

/** The mana pool (CR 106.4), or `null` when empty - unspent mana is the exception. */
private fun manaPoolLine(player: PlayerState): String? {
    if (player.manaPool.isEmpty()) return null
    return "  Mana pool: ${player.manaPool.joinToString("") { manaGlyph(it) }}"
}

/**
 * The battlefield permanents [seat] controls (CR 110.1), one per line. Controller equals owner in
 * the MVP pool (CR 108.4 - no control-changing effects), so ownership selects the seat's permanents.
 */
private fun battlefieldLines(
    state: GameState,
    seat: PlayerId,
): List<String> {
    val mine = state.sharedZones.battlefield.filter { it.owner == seat }
    if (mine.isEmpty()) return listOf("  Battlefield: (empty)")
    return listOf("  Battlefield:") + mine.map { "    - ${permanentLabel(state, it)}" }
}

/** The viewer's own hand contents (CR 402) - shown only for the viewer's seat. */
private fun handLines(
    state: GameState,
    player: PlayerState,
): List<String> {
    if (player.hand.isEmpty()) return listOf("  Hand: (empty)")
    return listOf("  Hand:") + player.hand.map { obj: GameObject -> "    - ${handCardLabel(state, obj)}" }
}
