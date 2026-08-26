package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.LibrarySearch
import dev.mtgplay.core.definition.LibrarySearchSearcher
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.resolutionController
import dev.mtgplay.core.state.resolutionSourceCard
import dev.mtgplay.core.state.resolutionTargets
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.effect.drawCards

/*
 * The two ends of a CR 701.18 search that `W9-F` made variable: **who** searches, and what happens
 * **after** the shuffle. Their own file because LibrarySearch.kt was at detekt's per-file function
 * budget; the search flow itself is unchanged and still lives there.
 */

/**
 * Which seat searches, and therefore which seat the engine pauses for (CR 701.18a).
 *
 * [LibrarySearchSearcher.CONTROLLER] is the resolving object's own controller, read from the entry and
 * needing no state at all. [LibrarySearchSearcher.TARGET_CONTROLLER] is the controller of the single
 * permanent the object targets, read from [beforeEffect] — the board as the object *began* resolving —
 * because CR 608.2h settles the answer once, as the effect is applied, and the effect that names this
 * searcher is the one that destroyed the permanent (Cleansing Wildfire). By the time the clause runs the
 * targeted land is a new object in a graveyard (CR 400.7) and the live battlefield has no answer to give.
 *
 * Control is ownership in the MVP pool (docs/design/layer-system.md §4), which is what makes the read a
 * single field. Fails loudly rather than falling back to the controller: a searcher named by a target the
 * CR 608.2b re-check just approved must be findable, and guessing here would silently hand the caster a
 * search the card gives to their opponent.
 */
internal fun librarySearchDecider(
    entry: StackEntry,
    searcher: LibrarySearchSearcher,
    beforeEffect: GameState,
): PlayerId =
    when (searcher) {
        LibrarySearchSearcher.CONTROLLER -> entry.resolutionController
        LibrarySearchSearcher.TARGET_CONTROLLER -> {
            val target =
                entry.resolutionTargets.singleOrNull() as? Target.Permanent
                    ?: error(
                        "CR 701.18a: a target-controller search needs exactly one permanent target, " +
                            "but ${entry.resolutionSourceCard.name} holds ${entry.resolutionTargets}",
                    )
            beforeEffect.sharedZones.battlefield
                .firstOrNull { it.id == target.id }
                ?.owner
                ?: error(
                    "CR 608.2h: ${entry.resolutionSourceCard.name}'s target ${target.id} was not on the " +
                        "battlefield as it began resolving, so it names no searcher",
                )
        }
    }

/**
 * The tail of a finished search (CR 701.18): the resolving object's controller draws
 * [LibrarySearch.thenDraw] cards, and the object then leaves the stack the way its own kind does.
 *
 * **The draw is the controller's, not the searcher's.** Cleansing Wildfire's trailing "Draw a card." is
 * the caster's sentence even though "its controller may search their library" hands the search itself to
 * the opponent — the only clause tail in the engine where the two seats can differ, which is why this
 * reads [StackEntry.resolutionController] rather than the pending record's decider.
 */
internal fun finishSearch(
    state: GameState,
    entry: StackEntry,
    search: LibrarySearch,
): AdvanceResult {
    val drawn =
        if (search.thenDraw > 0) drawCards(state, entry.resolutionController, search.thenDraw) else state
    return completeClauseResolution(drawn, entry)
}
