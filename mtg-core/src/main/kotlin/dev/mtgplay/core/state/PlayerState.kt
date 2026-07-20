package dev.mtgplay.core.state

import kotlinx.collections.immutable.PersistentList

/**
 * Everything the game tracks per player: the life total and the three per-player zones
 * (CR 400.2).
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
 * @property life the player's current life total (CR 119.1); may be negative.
 * @property library the player's library (CR 401); index 0 is the top.
 * @property hand the player's hand (CR 402).
 * @property graveyard the player's graveyard (CR 404); the last element is the top.
 */
data class PlayerState(
    val life: Int,
    val library: PersistentList<GameObject>,
    val hand: PersistentList<GameObject>,
    val graveyard: PersistentList<GameObject>,
)
