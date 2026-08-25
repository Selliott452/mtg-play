package dev.mtgplay.rules.engine

import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.effect.removeCounters

/*
 * The consequence of the CR 704.5q counter-annihilation state-based action (detected in
 * StateBasedActions.kt): a permanent carrying both +1/+1 and -1/-1 counters loses N of each, N being
 * the smaller count. Detection is a pure read; this file performs the removal. The same
 * detect-here/perform-there split AuraFallOff.kt and CreatureDeath.kt use.
 */

/**
 * Performs the CR 704.5q annihilations of one batch: from each named permanent still on the
 * battlefield, removes [StateBasedAction.CountersAnnihilate.amount] `+1/+1` counters and the same
 * number of `-1/-1` counters, emitting one [GameEvent.CountersRemoved] per kind.
 *
 * An object that left the battlefield earlier in this batch is skipped rather than failing: its
 * counters ceased to exist with the zone change (CR 122.2), so there is nothing left to remove and
 * nothing wrong with having planned to.
 */
internal fun performCounterAnnihilations(
    state: GameState,
    annihilations: List<StateBasedAction.CountersAnnihilate>,
): GameState =
    annihilations.fold(state) { current, action ->
        if (current.sharedZones.battlefield.none { it.id == action.objectId }) {
            current
        } else {
            removeCounters(
                removeCounters(current, action.objectId, Counter.PLUS_ONE_PLUS_ONE, action.amount),
                action.objectId,
                Counter.MINUS_ONE_MINUS_ONE,
                action.amount,
            )
        }
    }
