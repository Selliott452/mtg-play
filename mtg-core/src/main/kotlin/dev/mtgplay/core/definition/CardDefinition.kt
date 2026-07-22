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
}
