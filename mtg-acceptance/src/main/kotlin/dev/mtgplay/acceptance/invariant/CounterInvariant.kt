package dev.mtgplay.acceptance.invariant

import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.zone.ZoneId

/*
 * The [Invariant.COUNTER_SCOPE] check (CR 122). Counters are the state `FW-COUNTERS` added, and the
 * checker's charter is that new state gets a machine-checked well-formedness property in the same
 * packet — otherwise a counter leak shows up as a wrong power somewhere far from its cause.
 *
 * Its own file rather than another function in InvariantChecker.kt for the reason the checker's
 * header gives: it takes the minimal data (the residence list) so corruption a real `GameState`
 * cannot express is still directly testable.
 */

/**
 * [Invariant.COUNTER_SCOPE]: every counter multiset in [residences] is well-formed. Three arms, each
 * a distinct rule:
 *
 * 1. **Strictly positive multiplicities** (CR 122.1). A counter kind an object has none of is absent
 *    from the map, never present with a count of zero or less. This re-derives a `GameObject`
 *    construction guarantee, per the checker's phase-spanning charter — and it matters more than most
 *    such re-derivations, because a zero entry would make two states that are the *same* position
 *    compare unequal and hash apart in the replay fingerprint.
 * 2. **Battlefield-only scope** (CR 122.2). Counters are not retained when an object changes zones;
 *    they cease to exist. So an object anywhere but the battlefield carries none, and the fresh
 *    object born of a zone move (CR 400.7) carries none. A graveyard card with a `+1/+1` counter on
 *    it means some zone move copied state it should have dropped.
 * 3. **CR 704.5q quiescence.** No permanent has both a `+1/+1` and a `-1/-1` counter at a pause. The
 *    state-based action removes the matching pairs whenever a player would receive priority
 *    (CR 704.3), so finding such a permanent at a checkpoint means the action was not detected or not
 *    performed. The sibling of [Invariant.CREATURE_LETHALITY_RESOLVED], and checked for the same
 *    reason: a state-based action that quietly fails to fire is invisible until much later.
 */
internal fun checkCounterScope(residences: List<ZoneResidence>): List<Violation> =
    buildList {
        for (residence in residences) {
            val obj = residence.obj
            for ((kind, count) in obj.counters) {
                if (count <= 0) {
                    add(
                        Violation(
                            Invariant.COUNTER_SCOPE,
                            "CR 122.1: object ${obj.id.value} records $count $kind counters; a counter " +
                                "multiset holds only kinds that are present",
                        ),
                    )
                }
            }
            if (residence.zone != ZoneId.Battlefield && obj.counters.isNotEmpty()) {
                add(
                    Violation(
                        Invariant.COUNTER_SCOPE,
                        "CR 122.2: object ${obj.id.value} carries ${obj.counters} in ${residence.zone}, " +
                            "but counters are not retained when an object changes zones",
                    ),
                )
            }
            val plus = obj.counterCount(Counter.PLUS_ONE_PLUS_ONE)
            val minus = obj.counterCount(Counter.MINUS_ONE_MINUS_ONE)
            if (plus > 0 && minus > 0) {
                add(
                    Violation(
                        Invariant.COUNTER_SCOPE,
                        "CR 704.5q: object ${obj.id.value} has $plus +1/+1 and $minus -1/-1 counters at a " +
                            "pause; the state-based action removes ${minOf(plus, minus)} of each",
                    ),
                )
            }
        }
    }
