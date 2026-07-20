package dev.mtgplay.rules

import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId

/**
 * Everything needed to start a game deterministically (ADR-006): the PRNG seed, the per-seat
 * decks, the card-definition registry, and the pre-game parameters.
 * `(MatchConfig, List<Decision>)` fully reproduces a game.
 *
 * The engine never iterates [libraries] or [definitions] in the maps' own order — seats are
 * always processed in ascending seat order, and definitions are carried in name-sorted order —
 * so callers may pass any `Map` implementation without affecting determinism.
 *
 * @property seed the seed for the match-owned PRNG; every shuffle and random determination in
 *   the game derives from it (ADR-006).
 * @property libraries each seat's deck as an ordered list of printed-card references (CR 100.1);
 *   shuffled into that seat's library at game start (CR 103.1). Exactly two seats in P1.2.
 * @property definitions what each printed card *does* — the engine-facing [CardDefinition] per
 *   [CardRef]. A ref without a definition is an inert, uncastable card (architect decision,
 *   P2.1: every P1.x lands-only config stays valid unchanged); a rules path that requires a
 *   missing definition fails loudly.
 * @property startingHandSize how many cards each player draws as their opening hand; normally
 *   seven (CR 103.5). Mulligans are deferred to Phase 6 — hands are kept as drawn.
 * @property startingPlayer the seat that takes the first turn, or `null` to have the engine
 *   determine it at random from the match PRNG (CR 103.1) — still fully seed-determined.
 */
data class MatchConfig(
    val seed: Long,
    val libraries: Map<PlayerId, List<CardRef>>,
    val definitions: Map<CardRef, CardDefinition> = emptyMap(),
    val startingHandSize: Int = DEFAULT_STARTING_HAND_SIZE,
    val startingPlayer: PlayerId? = null,
) {
    init {
        require(libraries.size == SUPPORTED_PLAYER_COUNT) {
            "P1.2 supports exactly two-player games (CR 104.2a win condition); got ${libraries.size} seat(s)"
        }
        require(startingHandSize >= 0) { "starting hand size must be non-negative, was $startingHandSize" }
        val chosen = startingPlayer
        require(chosen == null || chosen in libraries) { "starting player $chosen has no seat in this match" }
        definitions.forEach { (ref, definition) ->
            require(definition.characteristics.name == ref.name) {
                "definition registered under ${ref.name} describes \"${definition.characteristics.name}\""
            }
        }
    }

    companion object {
        /** The normal starting hand size of seven cards (CR 103.5). */
        const val DEFAULT_STARTING_HAND_SIZE: Int = 7

        private const val SUPPORTED_PLAYER_COUNT: Int = 2
    }
}
