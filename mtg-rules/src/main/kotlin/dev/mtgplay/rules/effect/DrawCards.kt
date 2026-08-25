package dev.mtgplay.rules.effect

import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.countMatchingPermanents
import dev.mtgplay.rules.engine.drawCard

/**
 * Effect primitive: [player] draws [count] cards (CR 120.1) — the published building block a draw
 * effect composes (ADR-003; Abundant Growth's enters-the-battlefield "draw a card" is the first
 * client).
 *
 * Cards are drawn one at a time (CR 120.2) through the engine's draw move: each puts the top card of
 * [player]'s library into their hand as a new object (CR 400.7). A draw from an empty library fails
 * and records the CR 704.5c attempt on the player, which the state-based action acts on at the next
 * check — an effect never ends the game itself. Drawing zero cards changes nothing.
 */
fun drawCards(
    state: GameState,
    player: PlayerId,
    count: Int,
): GameState {
    require(count >= 0) { "CR 120.1: a draw count is non-negative, was $count" }
    return (0 until count).fold(state) { current, _ -> drawCard(current, player) }
}

/**
 * Effect primitive: **each player who controls a permanent matching [filter]** draws [count] cards
 * (CR 120.1, CR 109.4) — Bonder's Ornament's "Each player who controls a permanent named Bonder's
 * Ornament draws a card" (`W8-G`).
 *
 * A primitive rather than a fold left to the card, for [dealDamageToEachOpponent]'s reason and one more
 * of its own. **The draw is not controller-scoped**, which is the property that makes it a different verb
 * rather than a loop over [drawCards]: every other draw in the pool is "you draw" or "target player
 * draws" and reads a single seat off the resolution context, so a card writing this fold itself would be
 * re-deriving the seat set — and, worse, re-deriving *turn order*, which is the one thing the answer must
 * not get wrong.
 *
 * **Each qualifying player draws separately, in turn order**, exactly as [dealDamageToEachOpponent] deals
 * separately. CR 101.4 makes simultaneous player actions happen in APNAP order, and a draw is not truly
 * simultaneous anyway once a library runs out: the CR 704.5b attempt is recorded per player as it
 * happens, so the order decides which of two empty-library players is recorded first. Turn order is
 * [dev.mtgplay.core.state.GameState.players]' own canonical iteration order (ADR-006).
 *
 * **Who qualifies is settled once, before any card is drawn** (CR 608.2): the set is read off the board
 * as the ability begins to resolve, so nothing a draw does — a token ceasing, a library emptying — can
 * add or remove a player mid-effect. In practice no draw can change it, which is exactly why freezing it
 * costs nothing and states the rule out loud.
 *
 * [filter] must be controller-scoped ([PermanentFilter.controlledByYou]): "each player **who controls**"
 * asks the ownership question once per seat, and a board-wide filter would make every seat qualify the
 * moment any one of them did — the silently-wrong answer this refuses to give.
 */
fun drawCardsForEachPlayerControlling(
    state: GameState,
    filter: PermanentFilter,
    count: Int,
): GameState {
    require(count >= 0) { "CR 120.1: a draw count is non-negative, was $count" }
    require(filter.controlledByYou) {
        "CR 109.4: \"each player who controls a permanent\" is a per-seat control question, so the " +
            "filter must be controller-scoped, was $filter"
    }
    val drawers = state.players.keys.filter { countMatchingPermanents(state, filter, it) > 0 }
    return drawers.fold(state) { current, player -> drawCards(current, player, count) }
}
