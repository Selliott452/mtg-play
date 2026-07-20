package dev.mtgplay.core.event

/**
 * Why a player lost the game (CR 104.3) — the typed reason carried by both the
 * [GameEvent.PlayerLost] event and the rules engine's match result.
 *
 * Only the ways to lose that the P1.2 engine can actually produce are listed; later packets add
 * members alongside the state-based actions or effects that produce them (e.g. "loses the game"
 * effects in later phases).
 */
enum class LossReason {
    /**
     * The player's life total was 0 or less when state-based actions were checked (CR 704.5a).
     */
    LIFE_TOTAL_ZERO_OR_LESS,

    /**
     * The player attempted to draw a card from an empty library since the last time state-based
     * actions were checked (CR 104.3c, CR 704.5c) — the deck-out loss.
     */
    ATTEMPTED_DRAW_FROM_EMPTY_LIBRARY,
}
