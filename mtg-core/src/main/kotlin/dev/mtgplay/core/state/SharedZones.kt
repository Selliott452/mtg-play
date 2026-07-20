package dev.mtgplay.core.state

import kotlinx.collections.immutable.PersistentList

/**
 * The zones shared by all players (CR 400.2): the battlefield, the stack, and exile.
 *
 * Ordering conventions, fixed here for the whole engine:
 * - [battlefield] (CR 403) — no rules-relevant order; kept insertion-stable for determinism
 *   (see the iteration rule on [GameState]).
 * - [stack] (CR 405) — ordered, last in first out; the *last* element is the top, the next to
 *   resolve.
 * - [exile] (CR 406) — no rules-relevant order; kept insertion-stable.
 *
 * In P1.1 the stack holds plain [GameObject]s; the real representation of spells and abilities
 * on the stack arrives with the casting pipeline (P2.1), which owns reshaping this field.
 *
 * @property battlefield the shared battlefield (CR 403), where permanents exist.
 * @property stack the shared stack (CR 405); the last element is the top.
 * @property exile the shared exile zone (CR 406).
 */
data class SharedZones(
    val battlefield: PersistentList<GameObject>,
    val stack: PersistentList<GameObject>,
    val exile: PersistentList<GameObject>,
)
