package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.SpellRestriction
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry

/*
 * Interpreting a "counter target <kind of> spell" restriction (CR 115.1): whether a spell on the stack is
 * a legal choice for a spec carrying a [SpellRestriction].
 *
 * The stack sibling of [satisfiesPermanentRestriction] and [satisfiesEnchantRestriction], and split from
 * both for the same reason those two are separate from each other: they answer about different kinds of
 * object. Consulted by the one enumeration in `Targets.kt`, so cast-time legality (CR 601.2c), the
 * CR 608.2b resolution re-check, and the option list an agent sees (ADR-005) are the same predicate by
 * construction.
 *
 * Every characteristic is read through [spellCharacteristics], never off the cast record directly — the
 * CR 613 seam docs/design/countering-spells.md §5 asks for.
 */

/**
 * Whether the spell [entry] on the stack satisfies [restriction] (CR 115.1). Exhaustive over
 * [SpellRestriction] so a new restriction breaks compilation rather than being silently ignored.
 */
internal fun satisfiesSpellRestriction(
    state: GameState,
    restriction: SpellRestriction,
    entry: StackEntry.Spell,
): Boolean {
    val characteristics = spellCharacteristics(state, entry)
    return when (restriction) {
        SpellRestriction.Any -> true
        // CR 205.2: a card has a *set* of types, so "instant spell" is membership, not equality.
        is SpellRestriction.OfCardType -> restriction.cardType in characteristics.cardTypes
        is SpellRestriction.NotOfCardType -> restriction.cardType !in characteristics.cardTypes
        is SpellRestriction.OfAnyCardType -> restriction.cardTypes.any { it in characteristics.cardTypes }
        // CR 202.2: colour is derived from the mana cost here; a colour indicator (CR 204) is unmodeled
        // and would be silently mis-answered, which SpellRestriction.OfColor's KDoc records.
        is SpellRestriction.OfColor -> restriction.color in characteristics.colors
    }
}
