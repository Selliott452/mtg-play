package dev.mtgplay.core.state

import dev.mtgplay.core.identity.PlayerId

/**
 * One chosen target of a spell or ability (CR 115.1): the value recorded on a stack entry when
 * targets are chosen (CR 601.2c) and re-checked on resolution (CR 608.2b).
 *
 * Sealed so target-legality logic handles every kind exhaustively. P2.1's only member is
 * [Player] — nothing targetable exists on the battlefield until creatures arrive in Phase 3,
 * which adds an object-targeting member (by [dev.mtgplay.core.identity.ObjectId]) alongside the
 * battlefield state it refers to.
 */
sealed interface Target {
    /** A targeted player (CR 115.1a). */
    data class Player(
        val id: PlayerId,
    ) : Target
}
