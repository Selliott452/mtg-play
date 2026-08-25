package dev.mtgplay.rules.effect

import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.emit
import dev.mtgplay.rules.engine.millCard
import dev.mtgplay.rules.engine.player

/**
 * Effect primitive: [player] mills [count] cards (CR 701.13a) — the published building block a mill
 * effect composes (ADR-003; Thought Scour's "target player mills two cards" and Mental Note's "mill
 * two cards" are the first clients).
 *
 * Cards are milled one at a time, from the top of the library, each becoming a new graveyard object
 * (CR 400.7) narrated by [dev.mtgplay.core.event.GameEvent.CardMilled]. A library with fewer than
 * [count] cards mills as many as possible (CR 701.13b) and the effect does **not** fail: milling is
 * not drawing, so an empty library records no CR 704.5c draw attempt and no player loses for it.
 * Milling zero cards changes nothing.
 *
 * Milling is also not discarding (CR 701.13a vs CR 701.8a): the cards never pass through the hand,
 * so the CR 614/616 discard replacements — madness's "exile it instead" (CR 702.35a) — do not apply
 * to a milled card. That distinction is the reason this is its own primitive rather than a reuse of
 * the discard move.
 */
fun mill(
    state: GameState,
    player: PlayerId,
    count: Int,
): GameState {
    require(count >= 0) { "CR 701.13a: a mill count is non-negative, was $count" }
    return (0 until count).fold(state) { current, _ -> millCard(current, player) }
}

/**
 * Effect primitive: [player] reveals cards from the top of their library until they reveal one
 * satisfying [stopAt], then puts **all** the revealed cards into their graveyard (Balustrade Spy,
 * Undercity Informer). The revealed run is announced as one
 * [dev.mtgplay.core.event.GameEvent.CardsRevealed] before any card moves.
 *
 * **The stopping card is milled too**, and that is the printed wording rather than a choice: "reveals
 * cards … until they reveal a land card, then puts those cards into their graveyard" — "those cards"
 * is the whole revealed run, the land included. Milling up to but not including it would be a
 * different card.
 *
 * **A library that never satisfies [stopAt] is milled entirely, and does not lose the game.** This is
 * the case Spy Combo is built on: a deck with no lands reveals its whole library and stops because
 * there is nothing left to reveal, not because it found one. Milling is not drawing (CR 121.1 vs
 * CR 701.13a), so an emptied library records no CR 704.5c draw attempt and the player loses only when
 * they later try to draw from it. The loop is bounded by the library's size at entry rather than by
 * [stopAt], which is what makes "no card ever matches" terminate rather than spin — [millCard]
 * returns its input unchanged on an empty library, so a `while (!found)` loop would not.
 *
 * The reveal is public information (CR 701.15a), so it needs no per-seat filtering under ADR-007 and
 * no decision under ADR-005: which cards are revealed is settled entirely by the library's order,
 * which the seeded shuffle already fixed (ADR-006). Nothing here asks anybody anything.
 *
 * [stopAt] reads the card's printed identity rather than a game object, because every card in the run
 * is in a hidden zone until the instant it is revealed and has no other characteristics to consult.
 */
fun millUntil(
    state: GameState,
    player: PlayerId,
    stopAt: (CardRef) -> Boolean,
): GameState {
    val library = state.player(player).library
    // CR 701.15a: the run is the cards up to and including the first match, or the whole library when
    // there is no match at all. Computed before anything moves, so the reveal is one event.
    val revealed = library.takeWhile { !stopAt(it.card) }
    val runLength = if (revealed.size < library.size) revealed.size + 1 else library.size
    if (runLength == 0) return state
    val cards = library.take(runLength).map { it.card }
    val announced = state.emit(GameEvent.CardsRevealed(player, cards))
    return (0 until runLength).fold(announced) { current, _ -> millCard(current, player) }
}
