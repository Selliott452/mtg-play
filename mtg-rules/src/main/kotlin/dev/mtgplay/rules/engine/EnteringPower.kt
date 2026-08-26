package dev.mtgplay.rules.engine

import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState

/**
 * The power of the object [obj] as it entered the battlefield (CR 208.1, CR 613), or `0` for a
 * non-creature — the CR 603.10 last-known information an enters-the-battlefield trigger that reads its
 * own permanent's power falls back on. Boulderbranch Golem's "you gain life equal to its power" is the
 * pool's first client, through [dev.mtgplay.rules.effect.powerOfOrLastKnown]. Additive (`W9-G`).
 *
 * **The live value is what the ability actually uses**, and the primitive that reads it says so: the
 * amount an "equal to its power" clause gains is determined *as the ability resolves* (CR 608.2h), by
 * which point a counter, an Aura or a pump may have changed it. This capture is consulted only when the
 * permanent has **left the battlefield** by then, which is a reachable line rather than a theoretical
 * one — the trigger goes on the stack, both players get priority, and removal answers it.
 *
 * **Recorded deviation, not a silent one.** CR 608.2h wants the permanent's power *as it last existed on
 * the battlefield*; this is its power *as it entered*. The two differ only for a permanent whose power
 * changed after it entered and before it left, in a line where it also left before its own entry trigger
 * resolved. The engine has no last-known-information store to do better with, and inventing one here
 * would duplicate the framework a sibling packet owns; the exact answer arrives when that store does,
 * and this function is the single place that has to change.
 *
 * Read through [layeredCharacteristics], so a creature that entered with counters or under a
 * static ability is captured at its real power rather than its printed one.
 */
internal fun enteringPower(
    state: GameState,
    obj: GameObject,
): Int = layeredCharacteristics(state, obj.id).power ?: 0
