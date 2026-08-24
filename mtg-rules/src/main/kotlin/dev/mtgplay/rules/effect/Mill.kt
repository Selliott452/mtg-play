package dev.mtgplay.rules.effect

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.millCard

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
