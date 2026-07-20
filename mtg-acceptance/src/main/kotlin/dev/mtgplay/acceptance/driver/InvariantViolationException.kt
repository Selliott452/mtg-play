package dev.mtgplay.acceptance.driver

import dev.mtgplay.acceptance.invariant.Violation
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.Decision

/**
 * The scripted driver's loud failure when a transition produces a state that violates one or more
 * invariants (PLAN.md §2.3, §7: silent wrongness is the worst outcome).
 *
 * It carries everything needed to reproduce and diagnose: the [violations], the [decisions] played
 * up to and including the one that produced the bad state (a replay record, ADR-006), and the
 * offending [state]. The message lists the violations and the decision count so a failing run
 * points straight at the problem.
 *
 * @property violations the invariants the offending state broke.
 * @property decisions the decision log up to the failure, for replay.
 * @property state the state that failed the check.
 */
class InvariantViolationException(
    val violations: List<Violation>,
    val decisions: List<Decision>,
    val state: GameState,
) : IllegalStateException(describe(violations, decisions)) {
    private companion object {
        fun describe(
            violations: List<Violation>,
            decisions: List<Decision>,
        ): String =
            buildString {
                append("invariant check failed after ${decisions.size} decision(s) with ")
                append("${violations.size} violation(s):")
                violations.forEach { append("\n  - ${it.invariant}: ${it.detail}") }
            }
    }
}
