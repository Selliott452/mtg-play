package dev.mtgplay.acceptance.invariant

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState

/**
 * [Invariant.PENDING_RESOLUTION_SANITY]: each P6.2c mid-resolution pause — the optional cost-then-draw
 * (Highway Robbery, CR 601.3b), the mandatory resolution discard (Faithless Looting, CR 601.2c), and the
 * library search (Ash Barrens, CR 701.18) — is well-formed. Added in P6.2c. Two cheap properties of every
 * such pending record: its decider is a seated player, and the resolving object it hangs on is still on the
 * stack — these pauses occur while a spell or an activated ability is resolving, so an empty stack would mean
 * the pause outlived the object it belongs to. Top-level so the [InvariantChecker] file stays small.
 */
internal fun checkPendingResolutionSanity(state: GameState): List<Violation> =
    buildList {
        val stackIsEmpty = state.sharedZones.stack.isEmpty()

        fun checkPause(
            name: String,
            decider: PlayerId?,
        ) {
            if (decider == null) return
            if (decider !in state.players) {
                add(
                    Violation(
                        Invariant.PENDING_RESOLUTION_SANITY,
                        "CR 601/701: the $name pause names unseated decider $decider",
                    ),
                )
            }
            if (stackIsEmpty) {
                add(
                    Violation(
                        Invariant.PENDING_RESOLUTION_SANITY,
                        "CR 608.1: a $name pause is open but the stack is empty — the resolving object is gone",
                    ),
                )
            }
        }

        checkPause("optional cost-then-draw", state.pendingOptionalCostDraw?.decider)
        checkPause("resolution discard", state.pendingResolutionDiscard?.decider)
        checkPause("library search", state.pendingLibrarySearch?.decider)
    }
