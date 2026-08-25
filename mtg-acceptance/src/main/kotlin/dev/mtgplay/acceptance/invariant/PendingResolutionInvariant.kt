package dev.mtgplay.acceptance.invariant

import dev.mtgplay.core.definition.LibraryLookSource
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.resolutionClauses

/**
 * [Invariant.PENDING_RESOLUTION_SANITY]: each mid-resolution pause — the optional cost-then-draw
 * (Highway Robbery, CR 601.3b), the mandatory resolution discard (Faithless Looting, CR 601.2c), the
 * library search (Ash Barrens, CR 701.18), and the library reveal (Malevolent Rumble and Kruphix's
 * Insight, CR 701.16) — is well-formed. Added in P6.2c; the reveal pause and its keep allowance added in
 * P6.3. Two cheap properties of every such pending record: its decider is a seated player, and the
 * resolving object it hangs on is still on the stack — these pauses occur while a spell, a triggered
 * ability, or an activated ability is resolving, so an empty stack would mean the pause outlived the object
 * it belongs to. Since `FW-CLAUSEHOOK` every clause is read off the resolving object's
 * [dev.mtgplay.core.state.resolutionClauses] rather than off a spell definition, so an ability-carried
 * clause (Faerie Seer's scry) is checked by exactly these properties. The
 * reveal pause adds a third: the keeps gathered so far never exceed the resolving clause's
 * [dev.mtgplay.core.definition.LibraryReveal.toHandCount] allowance ("put up to three … into your hand").
 * Top-level so the [InvariantChecker] file stays small.
 *
 * The private library look (CR 701.14a, `FW-LIBLOOK`) joins them with three properties of its own: the
 * resolving object carries a look clause; every pool id is still resident in that clause's **source zone**,
 * because a looked-at card does not move until the arrangement is applied (CR 400.7); and the pool is empty
 * exactly while the pause is the clause's optional shuffle, by which point the cards have already moved.
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
        checkPause("library look", state.pendingLibraryLook?.decider)
        // CR 601.3b: the bare optional-draw clause (`FW-OPTDRAW`) pauses while its object is still resolving.
        checkPause("optional draw", state.pendingOptionalDraw?.decider)
        // CR 603.2: the "you may" that wraps a whole triggered ability (`W8-A`, Mortuary Mire).
        checkPause("optional trigger", state.pendingOptionalTrigger?.decider)
        // CR 118.3a: the one pause whose decider is the *targeted* spell's controller, not the resolving
        // counter's — so "is seated" is a real check here and not a restatement.
        checkPause("counter payment", state.pendingCounterPayment?.decider)
        addAll(checkLibraryLookPool(state))
        addAll(checkCounterPaymentTarget(state))

        val reveal = state.pendingRevealSelection
        val allowance =
            state.sharedZones.stack
                .lastOrNull()
                ?.resolutionClauses
                ?.libraryReveal
                ?.toHandCount
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

/**
 * The target half of the unless-pay pause (CR 118.3a, CR 701.5a): the spell that would be countered is
 * still on the stack. The CR 608.2b re-check runs before the pause is entered, so a target that has left
 * means the engine paused for a counter that should have fizzled — the ordering the whole Spell Pierce
 * case turns on.
 */
private fun checkCounterPaymentTarget(state: GameState): List<Violation> {
    val pending = state.pendingCounterPayment ?: return emptyList()
    val present =
        state.sharedZones.stack.any { (it as? StackEntry.Spell)?.obj?.id == pending.counteredObjectId }
    return if (present) {
        emptyList()
    } else {
        listOf(
            Violation(
                Invariant.PENDING_RESOLUTION_SANITY,
                "CR 608.2b: the unless-pay pause names spell ${pending.counteredObjectId}, which is not on " +
                    "the stack — its counter should have fizzled instead of asking for payment",
            ),
        )
    }
}

/**
 * The pool half of the library-look pause (CR 701.14a, CR 400.7): the resolving object carries a look
 * clause, every looked-at object is still resident in that clause's source zone — a look moves nothing
 * until the arrangement is applied — and the pool is empty exactly while the optional-shuffle stage is
 * pending, where the cards have already moved. Separate from [checkPendingResolutionSanity]'s body so that
 * function stays inside detekt's complexity budget.
 */
private fun checkLibraryLookPool(state: GameState): List<Violation> {
    val look = state.pendingLibraryLook ?: return emptyList()
    val clause =
        state.sharedZones.stack
            .lastOrNull()
            ?.resolutionClauses
            ?.libraryLook
    val decider = state.players[look.decider]
    val source = clause?.mode?.source
    val zone =
        when (source) {
            LibraryLookSource.TOP_OF_LIBRARY -> decider?.library
            LibraryLookSource.HAND -> decider?.hand
            null -> null
        }
    return buildList {
        if (zone == null) {
            add(
                Violation(
                    Invariant.PENDING_RESOLUTION_SANITY,
                    "CR 701.14a: a library-look pause is open with no resolving look clause or no seated decider",
                ),
            )
            return@buildList
        }
        val absent = look.poolIds.filterNot { id -> zone.any { it.id == id } }
        if (absent.isNotEmpty()) {
            add(
                Violation(
                    Invariant.PENDING_RESOLUTION_SANITY,
                    "CR 400.7: looked-at card(s) $absent left ${look.decider}'s " +
                        "$source before the arrangement was applied",
                ),
            )
        }
        if (look.awaitingShuffle != look.poolIds.isEmpty()) {
            add(
                Violation(
                    Invariant.PENDING_RESOLUTION_SANITY,
                    "CR 601.3b: a library-look pool is empty exactly at the shuffle stage; " +
                        "awaitingShuffle=${look.awaitingShuffle} with ${look.poolIds.size} card(s)",
                ),
            )
        }
    }
}
