package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target

/*
 * The characteristics of a spell **while it is on the stack** (CR 111, CR 613) — the single seam every
 * predicate about a spell on the stack reads through.
 *
 * Today it returns the printed characteristics captured on the cast record, unchanged, and this file is
 * therefore almost empty. It exists anyway, and every counter predicate goes through it rather than
 * touching `entry.definition.characteristics` directly, because CR 613 applies to a spell on the stack
 * exactly as it applies to a permanent — the engine's [layeredCharacteristics] is battlefield-only, so
 * there is currently nowhere else for a type-changing or colour-changing effect on a *spell* to land.
 * When the first one arrives, one function body changes and every counter predicate follows.
 *
 * The same discipline P3.1 used with `effectiveKeywords`/`effectivePower`, and the reason those seams
 * survived Phase 4 untouched (docs/design/countering-spells.md §5).
 */

/**
 * The characteristics of the spell [entry] as it sits on the stack (CR 111.1, CR 613). The printed
 * characteristics of the definition it was cast from — captured at cast time so resolution uses exactly
 * what was cast (CR 608.2c) — with no layer applied, because no effect in the pool changes a spell's
 * characteristics while it is on the stack.
 *
 * Note what is **not** rewritten: a spell cast for an alternative cost keeps its printed mana cost, so
 * its colours (CR 202.2) and its mana value (CR 202.3b) are the printed ones — a Fiery Temper cast for
 * its madness cost is still a red spell with mana value 3.
 */
internal fun spellCharacteristics(
    state: GameState,
    entry: StackEntry.Spell,
): PrintedCharacteristics {
    // The state is the seam's input for the day a continuous effect can reach a spell on the stack
    // (CR 613); today the answer depends on the cast record alone.
    check(state.sharedZones.stack.contains(entry)) {
        "CR 111.1: ${entry.obj.card.name} is not on the stack, so it is not a spell"
    }
    return entry.definition.characteristics
}

/**
 * The spell on [state]'s stack whose current stack-residence id is [id] (CR 400.7), or `null` when no
 * stack object has it — because the spell has already left the stack, or because [id] names an ability,
 * which is not a card and carries no such id (CR 113.7a).
 *
 * The lookup a [dev.mtgplay.core.state.Target.SpellOnStack] resolves through, wherever it appears. **By
 * id, never by position:** a countered spell is not in general directly below the counter (two counters
 * can stack above one spell), so nothing here may assume the stack's last index.
 */
internal fun spellOnStack(
    state: GameState,
    id: ObjectId,
): StackEntry.Spell? =
    state.sharedZones.stack
        .filterIsInstance<StackEntry.Spell>()
        .firstOrNull { it.obj.id == id }

/**
 * The colours of the object [target] names (CR 105, CR 202.2), or the empty set when it names nothing any
 * more — the shared derivation behind both the *targeting* restrictions
 * ([dev.mtgplay.core.definition.SpellRestriction.OfColor],
 * [dev.mtgplay.core.definition.PermanentRestriction.RED_PERMANENT]) and the *conditional-effect* test
 * [dev.mtgplay.rules.effect.targetIsColor], so a card's "target red spell" and another's "if it's red"
 * can never disagree about what red means.
 *
 * A spell on the stack reads through [spellCharacteristics], the CR 613 seam; a battlefield permanent
 * reads its printed characteristics, because CR 613's layer 5 (colour-changing) has no client in the pool
 * and [layeredCharacteristics] does not model it. Both derivations therefore come from the printed mana
 * cost and both carry the same CR 204 colour-indicator caveat, which
 * [dev.mtgplay.core.definition.SpellRestriction.OfColor] records.
 *
 * Fails loudly for a player or a graveyard card: neither is an object a colour predicate in this pool
 * addresses, and asking is a card-definition defect rather than a rules case.
 */
internal fun colorsOfTarget(
    state: GameState,
    target: Target,
): Set<Color> =
    when (target) {
        // Gone from the stack (countered, resolved, fizzled) — it is no object, so it is no colour.
        is Target.SpellOnStack ->
            spellOnStack(
                state,
                target.id,
            )?.let { spellCharacteristics(state, it).colors }.orEmpty()
        // Gone from the battlefield — likewise.
        is Target.Permanent ->
            state.sharedZones.battlefield
                .firstOrNull { it.id == target.id }
                ?.let { state.definitions[it.card]?.characteristics?.colors }
                .orEmpty()
        is Target.Player ->
            error("CR 105: a player has no colour, so $target cannot answer a colour condition")
        is Target.CardInGraveyard ->
            error("CR 105: no card in this pool tests the colour of a graveyard card, but $target was asked")
    }
