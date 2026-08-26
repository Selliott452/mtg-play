package dev.mtgplay.core.definition

import dev.mtgplay.core.mana.ManaCost
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/**
 * The [CardDefinition] refinement for a castable card: everything the CR 601 casting pipeline
 * needs that a plain definition does not carry.
 *
 * Lands are the deliberate non-member: a land is *played*, not cast (CR 115.2a, CR 305.1), so
 * a basic-land definition implements only [CardDefinition] and the play-land action (P2.2)
 * never touches this type. The casting pipeline requires a [SpellDefinition] and a printed
 * mana cost; additional and alternative costs (Grab the Prize, Fireblast — Phase 5,
 * docs/decklists.md) will extend the *pipeline's* cost-determination hook, not this contract.
 */
interface SpellDefinition :
    CardDefinition,
    ResolutionClauses {
    /** When this spell may be cast (CR 117.1a). */
    val timing: TimingClass

    /**
     * What this spell demands as targets (CR 115); [TargetSpec.None] for an untargeted spell.
     *
     * **Meaningless for a modal card**, whose targeting line belongs to the chosen mode (CR 601.2b);
     * [ModalSpell] overrides this to throw, and the engine reads a modal card's spec through the chosen
     * mode instead. Anything asking a spell what it targets must consult [modes] first.
     */
    val targetSpec: TargetSpec

    /**
     * The spell's resolution instructions (CR 608.2c).
     *
     * **Meaningless for a modal card**, whose instructions belong to the chosen mode (CR 700.2); see
     * [targetSpec] and [ModalSpell].
     */
    val resolution: ResolutionEffect

    /**
     * The printed modes of a **modal** card (CR 700.2), in printed order, or empty for the ordinary
     * card that has none. Additive, flagged core (`FW-MODAL`, docs/design/countering-spells.md §8) —
     * Blue and Red Elemental Blast, Hydroblast, Pyroblast, Steel Sabotage.
     *
     * Emptiness *is* the non-modal case, so `modes.isNotEmpty()` is the engine's one test for modality
     * and there is no second nullable flag that could disagree with it. A card that declares modes
     * implements [ModalSpell], which makes [targetSpec] and [resolution] throw — so declaring modes and
     * then forgetting to route a call site through them fails loudly rather than silently answering as
     * if the card were untargeted.
     *
     * The pool prints only "Choose one —". "Choose up to two" (Call Damage Control) and "Choose two"
     * additionally need a *count* on the declaration and a multi-select mode decision, and — since each
     * chosen mode brings its own targets — the multi-target framework this packet does not own; the
     * engine therefore requires exactly one chosen mode and fails loudly on any other arity.
     */
    val modes: PersistentList<SpellMode> get() = persistentListOf()

    /**
     * The printed mana cost (CR 202). Non-null by contract in P2.1: every castable fixture has
     * one, and cost determination (CR 601.2f) fails loudly on a spell without a mana cost until
     * the alternative-cost hook (Phase 5) gives "no mana cost" a meaning.
     */
    val manaCost: ManaCost? get() = characteristics.manaCost

    /**
     * The alternative ways this card may be cast beyond a normal cast from the hand (CR 601.2f) — the
     * cast-from-elsewhere permissions (madness, flashback, escape, plot; docs/decklists.md). Additive,
     * flagged core (P5.2); empty for a card castable only the normal way. The engine enumerates each
     * permission when it is legal (ADR-005) and runs the cast pipeline from the permission's source
     * zone at its alternative cost. Card definitions carry the *declaration*; `mtg-rules` carries the
     * rules.
     */
    val castingPermissions: List<CastingPermission> get() = emptyList()

    /**
     * The replacement effects this card carries that watch its own zone changes (CR 614). Additive,
     * flagged core (P5.2); empty for a card with no such replacement. The one member the MVP pool needs
     * here is [ReplacementEffect.DiscardToExileInstead] (madness's CR 702.35a "exile instead of
     * discard"), declared alongside the matching [CastingPermission.Madness]. Flashback's leave-stack
     * replacement is *not* here — it is a property of a flashback cast, carried on the cast record —
     * so this list holds only replacements intrinsic to the card wherever it sits.
     */
    val replacementEffects: PersistentList<ReplacementEffect> get() = persistentListOf()

    /**
     * The additional cost intrinsic to casting this spell (CR 601.2b), or `null` for a card with none.
     * Additive, flagged core (P6.2a). Grab the Prize's "As an additional cost to cast this spell,
     * discard a card" is [AdditionalCost.DiscardCards]`(1)`; the engine surfaces the selection, checks
     * payability, and performs the discard through the CR 614/616 framework (so madness intercepts it)
     * during payment (CR 601.2h). The discarded card's identity is recorded on the cast record as
     * linked information for the resolution.
     */
    val additionalCost: AdditionalCost? get() = null

    /*
     * The four post-resolution clauses -- libraryReveal, libraryLook, optionalCostThenDraw, and
     * drawThenDiscard -- are inherited from ResolutionClauses rather than declared here. They were
     * spell-shaped until `FW-CLAUSEHOOK` lifted them onto a carrier a triggered or activated ability
     * implements too, so the orchestration is written once for all three resolution paths; see
     * ResolutionClauses.kt for what each one means and docs/design/resolution-clause-hook.md for why.
     * A spell's clause runs after its ordinary [resolution] effect, then the spell leaves the stack.
     */

    /**
     * A "counter target spell **unless its controller pays** {N}" clause (CR 701.5, CR 118.3a), or `null`
     * for a spell with none. Additive, flagged core (`FW-COUNTER`, docs/design/countering-spells.md §7.1).
     * Force Spike's `{1}`, Spell Pierce's `{2}`.
     *
     * Declarative for the reason [drawThenDiscard] and [libraryReveal] are: the payment is a decision, and
     * ADR-004 forbids a callback out of a [ResolutionEffect]. The engine runs the clause **instead of** the
     * plain [resolution] for a spell that carries it, pausing for the targeted spell's controller to answer
     * — so a spell with this clause has no counter logic of its own to write.
     *
     * The CR 608.2b re-check still comes first: a counter whose target has already become an illegal
     * target fizzles, and **nobody is ever asked to pay**.
     */
    val counterUnlessPaid: CounterUnlessPaid? get() = null

    /**
     * This spell's **own** static cost-reduction ability (CR 601.2f), or `null` for a spell with none.
     * Additive, flagged core (`FW-COST`, docs/design/cost-modification.md §1). Affinity for artifacts
     * (CR 702.41a), the Terrors' graveyard count, and Of One Mind's conditional `{2}` all live here.
     *
     * A static ability of the spell itself, functioning while the spell is on the stack (CR 702.41a
     * says so for affinity, CR 604.5 generally) — which is why the declaration sits on the castable
     * refinement and not on [CardDefinition]. The other-object shape, where a *battlefield permanent*
     * reduces somebody's spells, is [CardDefinition.spellCostReductions] instead.
     *
     * The engine reads this exactly once per cast, at CR 601.2f, and the resulting total cost is
     * **locked in**: nothing paid afterwards re-prices the spell (the CR 601.2h example — sacrificing
     * the reducer as an additional cost still pays the reduced cost).
     */
    val costReduction: CostReduction? get() = null

    /**
     * This spell's **kicker** cost (CR 702.33), or `null` for a card without the keyword. Additive,
     * flagged core (`FW-OPTCOST`). Goblin Bushwhacker's `Kicker {R}`, Prohibit's `Kicker {2}`.
     *
     * **An *optional additional* cost, which is a shape nothing here had.** CR 702.33a: "Kicker [cost]"
     * means "You may pay an additional [cost] as you cast this spell." [additionalCost] is the
     * *mandatory* sibling — Grab the Prize discards whether you like it or not — and a
     * [CastingPermission] is an *alternative*, replacing the printed cost rather than adding to it
     * (CR 118.9). Kicker is neither: it is announced at CR 601.2b, it *adds* to the total cost at
     * CR 601.2f, and declining it is always legal.
     *
     * A bare [ManaCost] rather than a wrapper type, for the reason [rebound] is a bare `Boolean`:
     * there is exactly one thing to vary and the pool prints only mana kickers. CR 702.33d allows a
     * non-mana kicker cost ("Kicker—Sacrifice a creature") and CR 702.33c allows multikicker; each
     * would become its own type here rather than growing this one, and neither is printed in the
     * gauntlet.
     *
     * **Whether it was paid is linked information** (CR 702.33f), recorded on the cast record as
     * [dev.mtgplay.core.state.StackEntry.Spell.kicked] and, for a permanent, carried onto the entering
     * object as [dev.mtgplay.core.state.GameObject.kickedWhenCast]. That is what lets Prohibit's own
     * resolution and Goblin Bushwhacker's [InterveningIf] read it.
     *
     * **The announcement is only offered when the kicked cost is payable** (ADR-005): a seat that
     * cannot afford it is never asked a question whose "yes" would dead-end mid-cast. Declining is
     * always available, because a kicker only ever makes a cost larger.
     */
    val kicker: ManaCost? get() = null

    /**
     * This spell's **optional additional cost with a chosen object** (CR 601.2b), or `null` for a card
     * with none. Additive, flagged core (`FW-BARGAIN`). Troublemaker Ouphe's
     * [OptionalAdditionalCost.Bargain].
     *
     * The fourth cost declaration on this interface, and the one cell of the mandatory/optional by
     * mana/non-mana square that was empty — see [OptionalAdditionalCost] for the table and for why each
     * cell needs its own pipeline rather than a flag on a neighbour. Distinct from [kicker], which is
     * optional but has nothing to *choose*; from [additionalCost], which chooses but is mandatory; and
     * from [castingPermissions], which replace the printed cost rather than adding to it (CR 118.9).
     *
     * **At most one per card**, which the field's nullability enforces and which is what lets a single
     * recorded boolean answer "was it bargained?" unambiguously
     * ([dev.mtgplay.core.state.StackEntry.Spell.optionalCostPaid]).
     */
    val optionalAdditionalCost: OptionalAdditionalCost? get() = null

    /**
     * Whether this spell has **rebound** (CR 702.88a), the keyword Ephemerate prints. Additive, flagged
     * core (`FW-BLINK`, docs/design/exile-and-return.md §5).
     *
     * A `Boolean` rather than a data class because rebound is parameterless: CR 702.88a spells it out in
     * full as *"If this spell was cast from your hand, instead of putting it into your graveyard as it
     * resolves, exile it and, at the beginning of your next upkeep, you may cast this card from exile
     * without paying its mana cost"* — there is nothing for a card to vary. If a later keyword needs a
     * parameter it becomes its own type rather than growing this one.
     *
     * Two halves, both owned by `mtg-rules`. The **static** half functions while the spell is on the
     * stack and replaces the CR 608.2m graveyard move with an exile marked
     * [dev.mtgplay.core.state.GameObject.reboundTurn] — but *only* when the spell was cast from a hand
     * (CR 702.88a), so a rebounded Ephemerate cast the second time from exile finishes in the graveyard
     * and does not loop. The **delayed** half fires at the beginning of the controller's next upkeep as
     * [TriggerCondition.ReboundCast].
     */
    val rebound: Boolean get() = false

    /**
     * Whether this spell has **cascade** (CR 702.85a), the keyword Maelstrom Colossus prints. Additive,
     * flagged core (`W9-G`).
     *
     * A `Boolean` for [rebound]'s reason: CR 702.85a spells the keyword out in full and there is nothing
     * for a card to vary — *"When you cast this spell, exile cards from the top of your library until you
     * exile a nonland card whose mana value is less than this spell's mana value. You may cast that card
     * without paying its mana cost if the resulting spell's mana value is less than this spell's mana
     * value. Then put all cards exiled this way that weren't cast on the bottom of your library in a
     * random order."* Every number in that sentence is read off the cascading spell rather than printed
     * beside the keyword.
     *
     * **CR 702.85c — "if a spell has multiple instances of cascade, each triggers separately" — is why a
     * later card would need a count rather than a flag.** No gauntlet card prints two, so the honest
     * shape today is the boolean; a card that does would widen this to an `Int` and the engine would
     * synthesize that many triggers, which is a change to one line in the cast pipeline.
     *
     * Everything the keyword does is owned by `mtg-rules`: the ability is synthesized at CR 601.2i as a
     * [TriggerCondition.CascadeCast] trigger functioning from [TriggerZoneScope.Stack], and its
     * resolution is the engine's flow — exile until the predicate holds, offer the free cast via
     * [CastingPermission.Cascade], then bottom the rest through the match PRNG (ADR-006).
     */
    val cascade: Boolean get() = false
}
