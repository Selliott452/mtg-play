package dev.mtgplay.core.definition

import dev.mtgplay.core.card.PrintedCharacteristics
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/**
 * The engine-facing contract a defined card provides — the SPI `mtg-cards` implements (via the
 * DSL, P2.2) and the rules engine consumes.
 *
 * Deliberately minimal (no DSL here): printed characteristics plus the intrinsic mana
 * abilities the object has on the battlefield. A card that is *castable* implements the
 * [SpellDefinition] refinement. A [dev.mtgplay.core.identity.CardRef] with **no** definition in
 * the match's registry is an inert, uncastable card — legal to shuffle, draw, and discard, and
 * enumerated for none of it (architect decision, P2.1: this keeps every lands-only P1.x game
 * valid unchanged); a rules path that *requires* the missing definition fails loudly instead of
 * guessing.
 *
 * This contract lives in `mtg-core` rather than `mtg-rules` because definitions ride inside
 * [dev.mtgplay.core.state.GameState] (the registry, and each stack entry): the engine is a
 * stateless pure function of the state (ADR-004), so everything an `advance` needs — including
 * "which hand cards are castable" — must be reachable from the state alone, and core is the
 * only module state types may name. It is a noun by the PLAN.md §3 rule: data *about* a printed
 * card, with behaviour carried as pure-function values whose implementations live in
 * `mtg-cards` (and in rules-module test fixtures).
 */
interface CardDefinition {
    /** The card's printed characteristics (CR 109.3); its name must match the registry key. */
    val characteristics: PrintedCharacteristics

    /**
     * The intrinsic tap-for-mana abilities this card has as a battlefield object (CR 605.1a);
     * empty for a non-source. Listed here rather than on a battlefield-only subtype because
     * abilities exist on the card wherever it sits — zone-scoped *function* (CR 113.6) is the
     * engine's concern.
     */
    val manaAbilities: PersistentList<ManaAbility> get() = persistentListOf()

    /**
     * The continuous effects this card's static abilities generate (CR 604.3, CR 611.2); empty
     * for a card with no static ability. Additive, flagged core (P4.1). Every one is active while
     * this card is a battlefield permanent with the effect's affected set non-empty — for an Aura,
     * while it is attached to a legal object; `mtg-rules` classifies each into its CR 613 layer and
     * applies it (docs/design/layer-system.md §2). Card definitions carry the *declaration*; the
     * layer engine carries the rules.
     */
    val staticContinuousEffects: PersistentList<StaticContinuousEffect> get() = persistentListOf()

    /**
     * The triggered abilities this card has (CR 603); empty for a card with no triggered ability.
     * Additive, flagged core (P5.1). Each names the event pattern it watches for, the zone it
     * functions from, and its resolution effect ([TriggeredAbility]); `mtg-rules` detects when a
     * condition matches a game event (CR 603.3), orders simultaneous triggers in APNAP order
     * (CR 603.3b), puts each on the stack, and resolves it. Card definitions carry the
     * *declaration*; the trigger framework carries the rules.
     */
    val triggeredAbilities: PersistentList<TriggeredAbility> get() = persistentListOf()

    /**
     * The triggered **mana** abilities this card has (CR 605.1b); empty for a card with none. Additive,
     * flagged core (P6.2a). Utopia Sprawl's "whenever enchanted Forest is tapped for mana, add an
     * additional one mana of the chosen colour" is the one member the MVP pool needs; `mtg-rules`
     * resolves these inside a mana-ability resolution, no stack and no priority (CR 605.3).
     */
    val triggeredManaAbilities: PersistentList<TriggeredManaAbility> get() = persistentListOf()

    /**
     * Whether this permanent chooses a colour as it enters the battlefield (CR 614.12) — Utopia
     * Sprawl's "As this Aura enters, choose a colour". Additive, flagged core (P6.2a). `mtg-rules`
     * surfaces the colour decision during the permanent's resolution and stores the choice on the
     * entering object ([dev.mtgplay.core.state.GameObject.chosenColor]), where the card's
     * [triggeredManaAbilities] read it. `false` for every card that makes no such choice.
     */
    val choosesColorAsItEnters: Boolean get() = false

    /**
     * The activated abilities this card has (CR 602); empty for a card with none. Additive, flagged core
     * (P6.2a). Each names its composite cost, the zone it functions from, and its resolution effect
     * ([ActivatedAbility]); `mtg-rules` enumerates it when payable (ADR-005), gathers any cost selection,
     * puts it on the stack, and resolves it. Blood token's "{1}, {T}, Discard a card, Sacrifice this
     * token: Draw a card", Melded Moxite's "{3}, Sacrifice this artifact: Create a token", and Ash
     * Barrens' basic landcycling all live here.
     */
    val activatedAbilities: PersistentList<ActivatedAbility> get() = persistentListOf()

    /**
     * Whether this permanent enters the battlefield tapped (CR 614.1c) — "This land enters tapped",
     * printed on the Bridge artifact lands and on Idyllic Beachfront. Additive, flagged core (P8.4).
     *
     * A self-replacement effect, not an ability: it modifies the entering event itself, so it never uses
     * the stack, generates no trigger, and cannot be responded to. It is declared as a property here — the
     * shape [choosesColorAsItEnters] already established for CR 614.12 as-enters modifications on a
     * [CardDefinition] — rather than as a [ReplacementEffect] member, because [ReplacementEffect] is
     * declared on the castable [SpellDefinition] refinement and a land is never cast (CR 305.1).
     *
     * `mtg-rules` applies it at every point a permanent enters the battlefield: the play-land special
     * action (CR 305.1) and a resolving permanent spell's entry (CR 608.3). [EntersTapped.Never] for
     * every permanent that enters untapped by the CR 110.5a default.
     *
     * **Conditional clauses live here too, since `P-ETBTAPPED`.** This property was a `Boolean`, and
     * its KDoc recorded that "enters tapped unless you control three or more other Forests" was "a
     * different shape — it reads the game state as the permanent enters — and is not expressible
     * here; such a card stays unencoded rather than being approximated by `true` or `false`".
     * Gingerbread Cabin is that card, so the promise was kept by widening the type rather than by
     * approximating: [EntersTapped] is a sealed set of shapes, and the condition is read as the
     * permanent enters, exactly where the CR puts it.
     */
    val entersTapped: EntersTapped get() = EntersTapped.Never

    /**
     * The cost reductions this card's static abilities apply to **other spells its controller casts**
     * while it is a battlefield permanent (CR 604.5, CR 601.2f); empty for a card with no such
     * ability. Additive, flagged core (`FW-COST`, docs/design/cost-modification.md §1, C6). Sunscape
     * Familiar's "Green spells and blue spells you cast cost {1} less to cast" is the one shape the
     * gauntlet prints.
     *
     * On [CardDefinition] rather than [SpellDefinition] because the reader is a permanent, not a
     * spell: the reducer and the reduced are different objects, and a reducer need not be castable at
     * all. A spell's *own* reduction (affinity) is [SpellDefinition.costReduction].
     *
     * `mtg-rules` applies each while the card is on the battlefield under the caster's control, once
     * per matching permanent, at CR 601.2f only. Card definitions carry the *declaration*; the
     * cost-modification framework carries the rules.
     */
    val spellCostReductions: PersistentList<SpellCostReduction> get() = persistentListOf()
}
