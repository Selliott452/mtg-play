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
): Int = matchingPermanents(state, filter, you).size

/**
 * The battlefield permanents matching [filter] from the point of view of the player [you] (CR 109.4,
 * CR 205.3), **in battlefield order** — the option set of any enumerated choice over them, and the
 * membership [countMatchingPermanents] counts.
 *
 * The list and the count are one function so the two can never disagree: a cost enumerated against one
 * set and counted against another would offer an option its own payability check did not see, which is
 * the ADR-005 failure the joint derivations in `SacrificeCosts.kt` exist to prevent. Everything
 * [countMatchingPermanents] documents about *how* a permanent matches — controller is owner, subtypes
 * and card types are printed, keywords are layered, an object with no definition matches nothing —
 * lives here, because this is where it is decided.
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
): List<GameObject> =
    state.sharedZones.battlefield.filter { candidate ->
        val characteristics = state.definitions[candidate.card]?.characteristics
        characteristics != null &&
            (!filter.controlledByYou || candidate.owner == you) &&
            (filter.subtype == null || filter.subtype in characteristics.subtypes) &&
            (filter.cardType == null || filter.cardType in characteristics.cardTypes) &&
            (filter.keyword == null || filter.keyword in effectiveKeywords(state, candidate.id))
    }
