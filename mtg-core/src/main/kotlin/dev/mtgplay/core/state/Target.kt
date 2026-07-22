package dev.mtgplay.core.state

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId

/**
 * One thing a spell, ability, or combat-damage assignment refers to (CR 115.1, CR 120): the
 * value recorded on a stack entry when targets are chosen (CR 601.2c) and re-checked on
 * resolution (CR 608.2b), and the recipient shape the damage primitive addresses.
 *
 * Sealed so both target-legality logic and the damage primitive handle every kind exhaustively.
 * [Player] and [Permanent] are the P3.1 pair: a player (CR 115.1a) and a battlefield permanent
 * referenced by [dev.mtgplay.core.identity.ObjectId] (the object-targeting member Phase 3 adds
 * alongside the battlefield state it refers to — additive, flagged, P3.1). Combat damage never
 * *targets* (CR 509.1 blocking is not targeting), but a combat-damage recipient and a targetable
 * battlefield object coincide in this engine's scope, so both reuse [Permanent]. Target-legality
 * enumeration still offers players only — nothing enumerates a permanent as a legal *target*
 * until a spell in a later pool needs it; adding the member does not by itself make permanents
 * targetable (that is `legalTargets`' concern, `mtg-rules`).
 */
sealed interface Target {
    /** A player (CR 115.1a): a targeted player, or a player dealt damage (CR 120.3a). */
    data class Player(
        val id: PlayerId,
    ) : Target

    /**
     * A battlefield permanent, by its current-zone [id] (CR 115.1b, CR 120.3d). In P3.1 this is
     * a combat-damage recipient (an attacker or blocker taking marked damage); nothing yet
     * enumerates it as a legal spell target.
     */
    data class Permanent(
        val id: ObjectId,
    ) : Target
}
