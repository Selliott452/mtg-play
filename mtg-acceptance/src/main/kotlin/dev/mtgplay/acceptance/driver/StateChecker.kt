package dev.mtgplay.acceptance.driver

import dev.mtgplay.acceptance.invariant.CardCensus
import dev.mtgplay.acceptance.invariant.InvariantChecker
import dev.mtgplay.acceptance.invariant.Violation
import dev.mtgplay.core.state.GameState

/**
 * The invariant check the [ScriptedGame] runs after every transition, abstracted behind an
 * interface so it can be substituted in tests.
 *
 * Production driving uses [DEFAULT], which delegates to [InvariantChecker.check] with the game's
 * baseline card census. A test may inject a spy to prove the driver invokes the checker on every
 * transition, or a stricter check to probe the driver's fail-loud path.
 */
fun interface StateChecker {
    /** Returns the violations of [state] measured against the game's [baseline] card census. */
    fun check(
        state: GameState,
        baseline: CardCensus,
    ): List<Violation>

    companion object {
        /** The standard checker: the full [InvariantChecker] with card conservation enabled. */
        val DEFAULT: StateChecker = StateChecker { state, baseline -> InvariantChecker.check(state, baseline) }
    }
}
