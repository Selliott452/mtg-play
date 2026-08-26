package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.PermanentFilter
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
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
 *   `FW-TYPECHANGE` has since landed and did exactly what this note predicted: it extended
 *   [LayeredCharacteristics] with card types and subtypes, [hasSubtype] followed it, and this line did
 *   not move.
 * - **Card types are read layered too**, through [effectiveCardTypes] (CR 613 layer 4), which *is* a
 *   change `FW-TYPECHANGE` made here: a permanent that became an artifact is counted by an
 *   artifact-counting filter, and reading its printed type line would undercount by exactly the
 *   permanents a type-changing effect created.
 *
 * - **Names are read printed too**, and for a stronger reason than card types: CR 201.2 settles identity
 *   of name by the printed word alone, and the only effect that could change one is a CR 613 layer-3
 *   text-changing effect, a layer `Layers.kt` reserves and refuses. Bonder's Ornament's "a permanent
 *   named Bonder's Ornament" is the pool's first client (`W8-G`).
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
): Int = matchingPermanents(state, filter, you).size

/**
 * The battlefield permanents matching [filter] from the point of view of the player [you] (CR 109.4,
 * CR 205.3), **in battlefield order** — the option set of any enumerated choice over them, and the
 * membership [countMatchingPermanents] counts.
 *
 * The list and the count are one function so the two can never disagree: a cost enumerated against one
 * set and counted against another would offer an option its own payability check did not see, which is
 * the ADR-005 failure the joint derivations in `SacrificeCosts.kt` exist to prevent. Everything
 * [countMatchingPermanents] documents about *how* a permanent matches — controller is owner, names are
 * printed, card types and subtypes and keywords are all layered, an object with no definition matches
 * nothing — lives here, because this is where it is decided.
 *
 * Battlefield order is the engine's determinism spine (CR 613.7 timestamps derive from entry order), so
 * an option list built from it is stable across equal states (ADR-006).
 *
 * **Public rules surface** (ADR-003 vocabulary discipline), added by `FW-TAPUNTAP` for
 * [dev.mtgplay.core.definition.AbilityCost.ReturnPermanentYouControl] and
 * [dev.mtgplay.core.definition.PermanentSelection].
 */
fun matchingPermanents(
    state: GameState,
    filter: PermanentFilter,
    you: PlayerId,
): List<GameObject> {
    val subtype = filter.subtype
    return state.sharedZones.battlefield.filter { candidate ->
        val characteristics = state.definitions[candidate.card]?.characteristics
        characteristics != null &&
            (!filter.controlledByYou || candidate.owner == you) &&
            // CR 201.2: names are compared as printed; no CR 613 layer-3 text-changing effect exists.
            (filter.name == null || filter.name == characteristics.name) &&
            (subtype == null || hasSubtype(state, candidate.id, subtype)) &&
            // CR 613 layer 4: the in-game type line, so a permanent that *became* an artifact counts.
            (filter.cardType == null || filter.cardType in effectiveCardTypes(state, candidate.id)) &&
            (filter.keyword == null || filter.keyword in effectiveKeywords(state, candidate.id))
    }
}
