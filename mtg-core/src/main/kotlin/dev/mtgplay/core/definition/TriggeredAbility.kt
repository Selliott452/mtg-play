package dev.mtgplay.core.definition

/**
 * One triggered ability of a card (CR 603): a [condition] that fires it, the zone it functions from
 * ([zoneScope]), and the [effect] it performs on resolution. Card-definition data, additive and
 * flagged core (P5.1).
 *
 * **Core/rules split (ADR-009: "no game-rule decisions in core").** This declares *what* the ability
 * watches for and does; `mtg-rules` owns *detecting* the trigger (CR 603.3), *ordering* simultaneous
 * triggers in APNAP order (CR 603.3b), putting the ability on the stack, and resolving it (CR 608.2).
 * A triggered ability "uses the stack" and resolves like a spell (CR 603.3), except no card moves on
 * resolution — the ability simply performs [effect] and ceases to exist (CR 113.7a).
 *
 * The [effect] reuses the [ResolutionEffect] shape (P5.1 deliverable): the rules engine hands it a
 * [ResolutionContext] carrying the ability's controller plus the trigger's linked information —
 * [ResolutionContext.amount] (a damage-dealt "that much", CR 118.9) and [ResolutionContext.subject]
 * (a leaves-the-battlefield trigger's LKI object, CR 603.10) — and, for a targeting ability, the
 * targets chosen at CR 603.3d in [ResolutionContext.targets].
 *
 * **Post-resolution clauses ([ResolutionClauses]).** A triggered ability carries the same four clauses a
 * spell does — [libraryReveal], [libraryLook], [optionalCostThenDraw], and [drawThenDiscard] — because
 * `FW-CLAUSEHOOK` lifted them off [SpellDefinition] onto a carrier (docs/design/resolution-clause-hook.md).
 * Faerie Seer's "When this creature enters, scry 2" is CR 701.17a hanging off CR 603 rather than CR 601,
 * and the engine runs it through exactly the orchestration Preordain uses. The clause runs **after** the
 * ordinary [effect], and at most one may be declared. [optionalDiscardDraw] predates the carrier and is the
 * narrower trigger-only spelling of [optionalCostThenDraw]'s discard mode; it is kept as-is rather than
 * migrated, because retiring it would move wire-visible state.
 *
 * **Intervening-if conditions (CR 603.4) arrived with `FW-OPTCOST`** ([interveningIf]). The gap this
 * paragraph used to record — that putting the "if" inside [effect] implements only the resolution half
 * of the rule — is closed for the one shape the pool prints, "if it was kicked". Conditions of other
 * shapes are [InterveningIf]'s sealed extension point and still must not be written into [effect].
 *
 * @property condition the event pattern that fires this ability (CR 603.2).
 * @property interveningIf the CR 603.4 "intervening if" clause, or `null` for an ability with none.
 *   Checked **twice**: the ability does not trigger at all unless it holds, and it is removed from the
 *   stack doing nothing if it has stopped holding on resolution. Goblin Bushwhacker's "if it was
 *   kicked". Additive, flagged core (`FW-OPTCOST`).
 * @property effect what the ability does when it resolves (CR 608.2); reuses [ResolutionEffect].
 * @property targetSpec what this ability demands as targets (CR 115); [TargetSpec.None] for an
 *   untargeted ability. Additive, flagged core (`FW-ABILTGT`, docs/design/targeted-abilities.md).
 *   The engine chooses the targets **as the ability is put on the stack** (CR 603.3d), not when it
 *   fires and not when it resolves, and re-checks them on resolution (CR 608.2b). A triggered
 *   ability with no legal target is still put on the stack, with no targets, and then does nothing
 *   — unlike an activated ability, which cannot be activated at all in that position.
 * @property zoneScope the zone the ability functions from (CR 113.6); [TriggerZoneScope.Battlefield]
 *   for every MVP triggered half.
 * @property optionalDiscardDraw an optional "you may discard a card; if you do, draw N" clause the
 *   engine orchestrates on resolution (CR 603.2, CR 601.3b), or `null` for an ability with none.
 *   Additive, flagged core (P6.2a). Melded Moxite's enters-the-battlefield "you may discard a card. If
 *   you do, draw two cards." Because it needs a mid-resolution yes/no and discard selection, the engine
 *   runs it instead of [effect] (they are mutually exclusive in the MVP pool); the discard routes
 *   through the CR 614/616 framework so madness intercepts it.
 */
data class TriggeredAbility(
    val condition: TriggerCondition,
    val effect: ResolutionEffect,
    val zoneScope: TriggerZoneScope = TriggerZoneScope.Battlefield,
    val interveningIf: InterveningIf? = null,
    val optionalDiscardDraw: OptionalDiscardDraw? = null,
    val targetSpec: TargetSpec = TargetSpec.None,
    override val libraryReveal: LibraryReveal? = null,
    override val libraryLook: LibraryLook? = null,
    override val optionalCostThenDraw: OptionalCostThenDraw? = null,
    override val drawThenDiscard: DrawThenDiscard? = null,
    override val handRevealChoice: HandRevealChoice? = null,
    override val eachOpponentDiscards: EachOpponentDiscards? = null,
) : ResolutionClauses {
    init {
        requireAtMostOneClause(this) { "the $condition triggered ability" }
    }
}
