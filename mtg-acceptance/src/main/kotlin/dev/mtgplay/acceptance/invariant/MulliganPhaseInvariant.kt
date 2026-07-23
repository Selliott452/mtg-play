package dev.mtgplay.acceptance.invariant

import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PriorityStatus

/**
 * [Invariant.MULLIGAN_PHASE_SANITY]: the pre-game mulligan phase (CR 103.4/103.5), when running, is an
 * exclusive, priority-free pre-game position. A no-op when no mulligan is pending (P6.1). In its own
 * file to keep [InvariantChecker] within one concern per file.
 */
internal fun checkMulliganPhaseSanity(state: GameState): List<Violation> {
    val mulligan = state.pendingMulligan ?: return emptyList()
    val inGamePendingTransition =
        state.pendingCast != null ||
            state.pendingTriggers.isNotEmpty() ||
            state.pendingMadness != null ||
            state.pendingReplacement != null
    return buildList {
        if (mulligan.deciding !in state.players) {
            add(mulliganViolation("CR 103.5: the mulligan decider ${mulligan.deciding} is unseated"))
        }
        if (state.players.values.any { it.priorityStatus == PriorityStatus.HOLDS_PRIORITY }) {
            add(mulliganViolation("CR 103.4: a player holds priority during the mulligan phase"))
        }
        if (state.sharedZones.stack.isNotEmpty()) {
            add(mulliganViolation("CR 103.4: the stack is non-empty during the mulligan phase"))
        }
        if (inGamePendingTransition) {
            add(mulliganViolation("CR 103.4: an in-game pending transition coexists with the pre-game mulligan phase"))
        }
    }
}

private fun mulliganViolation(detail: String): Violation = Violation(Invariant.MULLIGAN_PHASE_SANITY, detail)
