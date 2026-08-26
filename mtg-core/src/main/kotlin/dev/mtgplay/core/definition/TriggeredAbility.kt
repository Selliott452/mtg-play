package dev.mtgplay.core.definition

import dev.mtgplay.core.mana.ManaType
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

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
 * **[librarySearch] was missing from this list until `W8-E`**, and its absence was a silent gap rather
 * than a decision. `P-SEARCH` lifted the search clause onto [ResolutionClauses] precisely so a spell or
 * a trigger could carry one, and [SpellDefinition] and [ActivatedAbility] both declared it — but this
 * type never did, so it inherited the interface's `null` default and a card whose *enters-the-battlefield
 * trigger* searches (Gatecreeper Vine, Sylvan Ranger, Civic Wayfinder) could not be written at all. The
 * carrier had the shape; one of its three implementors had not taken it.
 *
 * **Intervening-if conditions (CR 603.4) arrived with `FW-OPTCOST`** ([interveningIf]). The gap this
 * paragraph used to record — that putting the "if" inside [effect] implements only the resolution half
 * of the rule — is closed for the one shape the pool prints, "if it was kicked". Conditions of other
 * shapes are [InterveningIf]'s sealed extension point and still must not be written into [effect].
 *
 * @property condition the event pattern that fires this ability (CR 603.2).
 * @property optional whether the ability's **whole** effect is inside a printed "you may" (CR 603.2,
 *   CR 601.3b) — Mortuary Mire's "you may put target creature card from your graveyard on top of your
 *   library". Additive, flagged core (`W8-A`). `mtg-rules` pauses for the controller's yes/no when the
 *   ability resolves, after the CR 608.2b target re-check and the CR 603.4 intervening-if check, and
 *   performs [effect] only on acceptance.
 *
 *   **A flag on the ability rather than a clause, because the "may" wraps everything.** It is exact only
 *   for a printed line whose *entire* instruction set is optional, which is what every "you may &lt;do
 *   this&gt;" trigger in the gauntlet prints. A trigger with a mandatory half and an optional half is a
 *   different shape and must not be encoded with this flag — that would make the mandatory half
 *   declinable, which is the plausible-looking wrong card PLAN.md §7 is about; such a card needs a
 *   clause carrying its own effect, and is the extension point.
 *
 *   **It is not `TargetCount.UpTo(1)` in disguise.** A target is chosen as the ability goes on the stack
 *   (CR 603.3d) and the "may" is answered when it resolves, a whole priority round later and with
 *   different information; collapsing the two would move a real decision earlier (ADR-005).
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
 * @property librarySearch a "search your library, put one somewhere, then shuffle" part of this
 *   ability's resolution (CR 701.18), or `null` for an ability with none. Additive, flagged core
 *   (`W8-E`). Gatecreeper Vine's enters-the-battlefield "you may search your library for a basic land
 *   card or a Gate card". Run **after** the ordinary [effect], pausing for the find-one choice.
 *
 * @property counterUnlessPaid a "counter it unless that player pays [CounterUnlessPaid.cost]" part of this
 *   ability's resolution (CR 702.21a, CR 118.3a), or `null` for an ability with none. Additive, flagged
 *   core (`FW-WARD`) — the synthesized ward trigger, and nothing else in the pool.
 *
 *   The sibling of [SpellDefinition.counterUnlessPaid] and a separate property for the reason that one is
 *   not a [ResolutionClauses] member either: it runs **before** the ability's own [effect] rather than
 *   after it, and it names its victim differently — a spell reads its single
 *   [dev.mtgplay.core.state.Target.SpellOnStack] target, while a ward trigger reads the
 *   [dev.mtgplay.core.state.PendingTrigger.targetedBy] it captured when it fired, because ward's trigger
 *   does not target at all.
 * @property addsMana the mana this ability's resolution adds to its controller's pool (CR 106.1,
 *   CR 106.4), in printed order; empty for every ability that adds none. Additive, flagged core
 *   (`W8-B`) — Burning-Tree Emissary's "When this creature enters, add `{R}{G}`".
 *
 *   **Not a [TriggeredManaAbility], and the difference is the stack.** CR 605.1b makes a triggered
 *   ability a *mana* ability only if it triggers off the activation or resolution of a mana ability;
 *   this one triggers off a permanent entering the battlefield (CR 603.2), so it is an ordinary
 *   triggered ability that uses the stack, is put on it in APNAP order, and can be responded to. That
 *   is the whole card: an opponent gets a window between the Emissary entering and its mana arriving.
 *
 *   **A declaration rather than a `dealMana`-style [effect], for two reasons that are not style.**
 *   First, the mana pool is engine bookkeeping (CR 106.4) and the acceptance module's
 *   `MANA_POOL_EMPTY_AT_PAUSE` invariant has to know, *from the definitions alone*, which seats may
 *   legitimately hold floating mana at a pause — a lambda hides that and the invariant would report
 *   a correct game as engine wrongness. Second, a declaration cannot drift from what the engine does,
 *   because the engine is what does it.
 */
data class TriggeredAbility(
    val condition: TriggerCondition,
    val effect: ResolutionEffect,
    val optional: Boolean = false,
    val zoneScope: TriggerZoneScope = TriggerZoneScope.Battlefield,
    val interveningIf: InterveningIf? = null,
    val optionalDiscardDraw: OptionalDiscardDraw? = null,
    val targetSpec: TargetSpec = TargetSpec.None,
    override val libraryReveal: LibraryReveal? = null,
    override val libraryLook: LibraryLook? = null,
    override val librarySearch: LibrarySearch? = null,
    override val optionalCostThenDraw: OptionalCostThenDraw? = null,
    override val drawThenDiscard: DrawThenDiscard? = null,
    override val handRevealChoice: HandRevealChoice? = null,
    override val eachOpponentDiscards: EachOpponentDiscards? = null,
    override val optionalDraw: OptionalDraw? = null,
    override val permanentSelection: PermanentSelection? = null,
    val addsMana: PersistentList<ManaType> = persistentListOf(),
    override val optionalTapOrUntap: OptionalTapOrUntap? = null,
    override val optionalManaThenDraw: OptionalManaThenDraw? = null,
    override val targetPlayerExilesFromGraveyard: TargetPlayerExilesFromGraveyard? = null,
    override val chosenTypeReveal: ChosenTypeReveal? = null,
    override val optionalDrawThenDiscard: OptionalDrawThenDiscard? = null,
    val counterUnlessPaid: CounterUnlessPaid? = null,
    override val optionalGraveyardExileGate: OptionalGraveyardExileGate? = null,
) : ResolutionClauses {
    init {
        requireAtMostOneClause(this) { "the $condition triggered ability" }
    }
}
