package dev.mtgplay.acceptance.invariant

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry

/**
 * [Invariant.PENDING_RESOLUTION_SANITY]: each mid-resolution pause — the optional cost-then-draw
 * (Highway Robbery, CR 601.3b), the mandatory resolution discard (Faithless Looting, CR 601.2c), the
 * library search (Ash Barrens, CR 701.18), and the library reveal (Malevolent Rumble and Kruphix's
 * Insight, CR 701.16) — is well-formed. Added in P6.2c; the reveal pause and its keep allowance added in
 * P6.3. Two cheap properties of every such pending record: its decider is a seated player, and the
 * resolving object it hangs on is still on the stack — these pauses occur while a spell or an activated
 * ability is resolving, so an empty stack would mean the pause outlived the object it belongs to. The
 * reveal pause adds a third: the keeps gathered so far never exceed the resolving clause's
 * [dev.mtgplay.core.definition.LibraryReveal.toHandCount] allowance ("put up to three … into your hand").
 * Top-level so the [InvariantChecker] file stays small.
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
        checkPause("library reveal", state.pendingRevealSelection?.decider)

        val reveal = state.pendingRevealSelection
        val allowance =
            (state.sharedZones.stack.lastOrNull() as? StackEntry.Spell)?.definition?.libraryReveal?.toHandCount
        if (reveal != null && allowance != null && reveal.keptIds.size > allowance) {
            add(
                Violation(
                    Invariant.PENDING_RESOLUTION_SANITY,
                    "CR 701.16: the library-reveal pause has kept ${reveal.keptIds.size} cards, " +
                        "over its allowance of $allowance",
                ),
            )
        }
    }
