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
 * the ability's controller and, for a targeting ability, the targets chosen at CR 602.2b.
 *
 * **Post-resolution clauses ([ResolutionClauses]).** An activated ability carries the same five clauses a
 * spell does — [libraryReveal], [libraryLook], [optionalCostThenDraw], [drawThenDiscard], and
 * [librarySearch] — because `FW-CLAUSEHOOK` lifted them off [SpellDefinition] onto a carrier
 * (docs/design/resolution-clause-hook.md), so an activated ability that scries or loots is one clause
 * rather than a second orchestration. The clause runs **after** the ordinary [effect], and at most one may
 * be declared. [librarySearch] joined them in `P-SEARCH` (docs/design/library-search.md §2): it had been a
 * field of this type alone, which made a searching *spell* inexpressible and ran the search instead of the
 * ordinary effect rather than after it.
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
 * @property timing when this ability may be activated (CR 602.5d). [TimingClass.INSTANT_SPEED] is the
 *   CR 602.5a default — an activated ability may be activated whenever its controller has priority — and
 *   [TimingClass.SORCERY_SPEED] is the printed restriction "Activate only as a sorcery", which CR 602.5d
 *   defines as "the player must follow the timing rules for casting a sorcery spell, though the ability
 *   isn't actually a sorcery". Additive, flagged core (`FW-MANACOST`). Basilisk Gate and Timberwatch Elf
 *   print it; without the field they would encode as instant-speed tricks, which is an
 *   enumerated-but-illegal action (ADR-005) rather than a cosmetic inaccuracy. The window itself is the
 *   *same* predicate a sorcery's cast is checked against, so the two can never drift apart.
 * @property targetSpec what this ability demands as targets (CR 115); [TargetSpec.None] for an untargeted
 *   ability. Additive, flagged core (`FW-ABILTGT`, docs/design/targeted-abilities.md). The targets are
 *   chosen as part of activating the ability (CR 602.2b, following CR 601.2b–i) — before any cost is paid
 *   — and re-checked on resolution (CR 608.2b). An activated ability with no legal target **cannot be
 *   activated** (CR 601.2c) and so is never enumerated, unlike a triggered ability, which is still put on
 *   the stack in that position.
 */
data class ActivatedAbility(
    val cost: PersistentList<AbilityCost>,
    val effect: ResolutionEffect,
    val zoneScope: AbilityZoneScope = AbilityZoneScope.Battlefield,
    override val librarySearch: LibrarySearch? = null,
    val timing: TimingClass = TimingClass.INSTANT_SPEED,
    val targetSpec: TargetSpec = TargetSpec.None,
    override val libraryReveal: LibraryReveal? = null,
    override val libraryLook: LibraryLook? = null,
    override val optionalCostThenDraw: OptionalCostThenDraw? = null,
    override val drawThenDiscard: DrawThenDiscard? = null,
    override val handRevealChoice: HandRevealChoice? = null,
    override val eachOpponentDiscards: EachOpponentDiscards? = null,
) : ResolutionClauses {
    init {
        require(cost.isNotEmpty()) { "CR 602.1: an activated ability has a cost" }
        requireAtMostOneClause(this) { "the activated ability costing $cost" }
    }
}
