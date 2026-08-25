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
 * - **Subtypes are read through the one changeling-aware seam**, [hasSubtype], not by testing the
 *   printed set directly. [dev.mtgplay.core.card.Keyword.CHANGELING] (CR 702.73a) makes a
 *   Shapeshifter every creature type, so Priest of Titania and Wellwisher must count Rooftop Percher
 *   as an Elf while Gingerbread Cabin must **not** count it as a Forest — a distinction the seam draws
 *   with [dev.mtgplay.core.card.Subtype.isCreatureType] and a bare set membership cannot draw at all.
 *   Layer-4 type *changing* remains absent (`FW-TYPECHANGE`, its own framework); when it lands it
 *   extends [LayeredCharacteristics] and the seam follows it, and this line does not move again.
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
): Int {
    val subtype = filter.subtype
    return state.sharedZones.battlefield.count { candidate ->
        val characteristics = state.definitions[candidate.card]?.characteristics
        characteristics != null &&
            (!filter.controlledByYou || candidate.owner == you) &&
            (subtype == null || hasSubtype(state, candidate.id, subtype)) &&
            (filter.cardType == null || filter.cardType in characteristics.cardTypes) &&
            (filter.keyword == null || filter.keyword in effectiveKeywords(state, candidate.id))
    }
}
