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
interface SpellDefinition : CardDefinition {
    /** When this spell may be cast (CR 117.1a). */
    val timing: TimingClass

    /** What this spell demands as targets (CR 115); [TargetSpec.None] for an untargeted spell. */
    val targetSpec: TargetSpec

    /** The spell's resolution instructions (CR 608.2c). */
    val resolution: ResolutionEffect

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

    /**
     * A "reveal top N, put one into hand, rest into graveyard" part of this spell's resolution
     * (CR 701.16), or `null` for a spell with none. Additive, flagged core (P6.2a). Malevolent Rumble's
     * reveal-four clause. Because it needs a mid-resolution selection, the engine runs it after the
     * ordinary [resolution] effect (the two clauses are independent), pausing for the choice.
     */
    val libraryReveal: LibraryReveal? get() = null

    /**
     * A private "look at these cards, then arrange them between the top of your library, the bottom of your
     * library, and your hand" part of this spell's resolution (CR 701.14, CR 701.17), or `null` for a spell
     * with none. Additive, flagged core (`FW-LIBLOOK`, docs/design/library-look.md). Preordain's scry 2,
     * Ponder's reorder-the-top-three, Impulse's one-to-hand, Brainstorm's two-from-hand-on-top.
     *
     * The sibling of [libraryReveal], never a mode of it: a look is private (CR 701.14a) where a reveal is
     * public (CR 701.16a), and its whole decision is an *ordering*. Because it needs a mid-resolution
     * decision, the engine runs it after the ordinary [resolution] effect, pausing for the arrangement, the
     * optional shuffle, and then performing the clause's trailing draw.
     */
    val libraryLook: LibraryLook? get() = null

    /**
     * An optional "you may [discard a card | sacrifice a land]; if you do, draw N" part of this spell's
     * resolution (CR 601.3b), or `null` for a spell with none. Additive, flagged core (P6.2c). Highway
     * Robbery's clause. Because it needs a mode choice and then a cost-object selection, the engine runs it
     * after the ordinary [resolution] effect, pausing for those decisions — the spell-resolution generalizer
     * of the trigger-scoped [OptionalDiscardDraw], plus the sacrifice-a-land alternative mode.
     */
    val optionalCostThenDraw: OptionalCostThenDraw? get() = null

    /**
     * A mandatory "draw N cards, then discard M cards" part of this spell's resolution (CR 601.2c), or
     * `null` for a spell with none. Additive, flagged core (P6.2c). Faithless Looting's "Draw two cards,
     * then discard two cards." The engine runs it as the resolution — it draws, then pauses for the
     * mandatory discard-M selection (routed through the CR 614/616 framework, so a discarded madness card is
     * exiled instead), then the spell leaves the stack.
     */
    val drawThenDiscard: DrawThenDiscard? get() = null
}
