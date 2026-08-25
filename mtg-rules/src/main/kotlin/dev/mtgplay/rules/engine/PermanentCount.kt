package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState

/*
 * Counting the battlefield permanents a card's text picks out (CR 109.4, CR 205.3) — the shared
 * noun of docs/design/cost-modification.md §6.
 *
 * That note's verdict was "extract the predicate and the count; do **not** extract the consumer":
 * the count is one small function, but its *read point* differs per consumer and none of the read
 * semantics converts into another. A mana ability's amount is read live, per activation, mid-payment
 * (CR 605.2); an until-end-of-turn magnitude is read **once**, on resolution, and frozen for the
 * effect's whole duration (CR 608.2h, CR 611.2d); a static Aura's magnitude is read on every
 * characteristic computation (CR 613.3c). This file therefore holds the count and nothing else —
 * each caller names its own read point in its own type (docs/design/duration.md §3).
 *
 * It was lifted, unchanged in behaviour, out of `ManaSourceClass.kt` at the moment a second consumer
 * appeared.
 */

/**
 * How many battlefield permanents match [filter] from the point of view of the player [you]
 * (CR 109.4, CR 205.3): permanents whose **printed** subtypes contain the filter's subtype, and —
 * when the filter says "you control" — that are controlled by [you].
 *
 * Two read points are worth naming.
 *
 * - **Controller is owner**, the same standing simplification the rest of the engine makes until
 *   control-changing effects exist (CR 613 layer 2, docs/design/layer-system.md §4).
 * - **Subtypes are printed, not layered.** [LayeredCharacteristics] carries power, toughness,
 *   keywords and mana abilities, and no card in the gauntlet pool changes a permanent's subtypes
 *   (that is `FW-TYPECHANGE`, its own framework). Reading printed subtypes is therefore exact today;
 *   when a type-changing effect lands it must extend [LayeredCharacteristics] and this line must
 *   follow it, and the reason it will be found is that this is the only place subtypes are counted.
 *
 * An object with no definition is inert and matches nothing — the engine cannot know what it is.
 *
 * **Public rules surface** (ADR-003 vocabulary discipline): a card whose text counts permanents —
 * Timberwatch Elf's "the number of Elves on the battlefield" — composes this rather than re-deriving
 * subtype matching in `mtg-cards`, which is how the two would drift. Flagged in the `FW-DURATION`
 * packet report.
 */
fun countMatchingPermanents(
    state: GameState,
    filter: PermanentFilter,
    you: PlayerId,
): Int =
    state.sharedZones.battlefield.count { candidate ->
        (!filter.controlledByYou || candidate.owner == you) &&
            state.definitions[candidate.card]
                ?.characteristics
                ?.subtypes
                ?.contains(filter.subtype) == true
    }
