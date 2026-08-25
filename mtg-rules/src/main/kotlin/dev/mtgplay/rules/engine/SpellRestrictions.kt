package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.ManaValueBound
import dev.mtgplay.core.definition.SpellRestriction
import dev.mtgplay.core.identity.PlayerId
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
 *
 * [you] is the player doing the choosing — the caster at CR 601.2c, the ability's controller at
 * CR 603.3d, and the same player again at the CR 608.2b re-check. Most restrictions ignore it and
 * asked a pure question about the spell; [SpellRestriction.OfManaValueAtMost] with a
 * [ManaValueBound.PerMatching] is the first that does not, because "the number of Faeries **you**
 * control" is CR 109.5's "you" and so is a question about the board *and* who is asking. That is the
 * shape [satisfiesPermanentRestriction] already had, arriving on the stack.
 */
internal fun satisfiesSpellRestriction(
    state: GameState,
    restriction: SpellRestriction,
    entry: StackEntry.Spell,
    you: PlayerId,
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
        // CR 202.3: the *spell's* mana value, which an alternative cost does not change (CR 202.3b) —
        // a madness-cast Fiery Temper is still mana value 3 here.
        is SpellRestriction.OfManaValueAtMost ->
            characteristics.manaValue <= manaValueBound(state, restriction.bound, you)
    }
}

/**
 * The number a [SpellRestriction.OfManaValueAtMost] compares against (CR 202.3), for the deciding
 * player [you].
 *
 * **Evaluated on every call rather than cached**, and that is the whole behaviour of Spellstutter
 * Sprite's "where X is the number of Faeries you control": the bound is read at the CR 603.3d choice
 * and read *again* at the CR 608.2b re-check, so a Faerie that dies in response shrinks X and can make
 * an already-chosen target illegal. Caching it at the choice would silently counter a spell the Sprite
 * no longer reaches — and the enumeration is the only place that can get this right, because the
 * enumeration *is* the legality test (ADR-005).
 *
 * Counting reuses [countMatching], the same function cost reduction reads, with **no exclusion**: the
 * counting object is on the battlefield and counts itself (see [ManaValueBound.PerMatching]).
 */
private fun manaValueBound(
    state: GameState,
    bound: ManaValueBound,
    you: PlayerId,
): Int =
    when (bound) {
        is ManaValueBound.Fixed -> bound.value
        is ManaValueBound.PerMatching -> countMatching(state, you, bound.scope, bound.predicate)
    }
