package dev.mtgplay.core.random

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList

/**
 * Deterministic Fisher–Yates shuffle over a persistent list — the shuffle behind the pre-game
 * shuffle (CR 103.1) and any in-game shuffle effect, e.g. the search-and-shuffle of Ash
 * Barrens' basic landcycling.
 *
 * Like [Rng] itself, the exact draw order is part of the frozen replay contract (ADR-006):
 * iterate `i` from `size - 1` down to `1`, draw `j = nextInt(i + 1)`, swap positions `i` and
 * `j`. The known-answer tests pin it; it must never change.
 *
 * Pure: returns the shuffled list and the successor [Rng]; the receiver and the passed [rng]
 * are unchanged. A list of fewer than two elements comes back equal to the receiver with the
 * generator undrawn.
 */
fun <T> PersistentList<T>.shuffled(rng: Rng): Pair<PersistentList<T>, Rng> {
    val working = toMutableList()
    var current = rng
    for (i in working.lastIndex downTo 1) {
        val (j, successor) = current.nextInt(i + 1)
        current = successor
        val swapped = working[i]
        working[i] = working[j]
        working[j] = swapped
    }
    return working.toPersistentList() to current
}
