package dev.mtgplay.core.definition

import kotlinx.collections.immutable.PersistentList

/**
 * One activated ability of a card (CR 602): a composite [cost] (a list of [AbilityCost] components), the
 * zone it functions from ([zoneScope]), and the [effect] it performs on resolution. Card-definition
 * data, additive and flagged core (P6.2a).
 *
 * **Core/rules split (ADR-009: "no game-rule decisions in core").** This declares *what* it costs and
 * does; `mtg-rules` owns whether the cost is payable, gathering any cost selection (a card to discard, a
 * payment plan), putting the ability on the stack as an activated-ability object (CR 602.2, CR 113.3),
 * and resolving it. An activated ability "uses the stack" and resolves like a triggered ability
 * (CR 602.2b–c), except no card moves on resolution — it simply performs [effect] and ceases to exist
 * (CR 113.7a). (Mana abilities — CR 605 — are a separate, stackless path and are not modeled here.)
 *
 * The [effect] reuses the [ResolutionEffect] shape: the engine hands it a [ResolutionContext] carrying
 * the ability's controller. Targeted activated abilities (CR 602.2b) are the extension point; no MVP
 * activated ability targets.
 *
 * @property cost the ability's composite activation cost (CR 602.1), in printed order; never empty.
 * @property effect what the ability does when it resolves (CR 608.2); reuses [ResolutionEffect]. A no-op for
 *   an ability whose whole effect is a [librarySearch] (Ash Barrens), which the engine orchestrates instead.
 * @property zoneScope the zone the ability functions from (CR 113.6); [AbilityZoneScope.Battlefield]
 *   for most, [AbilityZoneScope.Hand] for landcycling.
 * @property librarySearch a "search your library, put one into hand, then shuffle" part of this ability's
 *   resolution (CR 701.18), or `null` for an ability with none. Additive, flagged core (P6.2c). Ash Barrens'
 *   basic landcycling. Because it needs a mid-resolution selection and a seeded shuffle, `mtg-rules` runs it
 *   after the ordinary [effect], pausing for the find-one choice.
 */
data class ActivatedAbility(
    val cost: PersistentList<AbilityCost>,
    val effect: ResolutionEffect,
    val zoneScope: AbilityZoneScope = AbilityZoneScope.Battlefield,
    val librarySearch: LibrarySearch? = null,
) {
    init {
        require(cost.isNotEmpty()) { "CR 602.1: an activated ability has a cost" }
    }
}
