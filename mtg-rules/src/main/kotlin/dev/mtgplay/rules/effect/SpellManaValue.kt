package dev.mtgplay.rules.effect

import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.engine.spellManaValue

/**
 * The mana value of the spell [target] names, **as a spell on the stack** (CR 202.3, CR 202.3b) — the
 * published read a card's conditional effect uses when it asks how big another spell is. Prohibit's
 * "counter that spell if its mana value is 2 or less" is the first client. Additive (`FW-X`).
 *
 * **CR 202.3b is the whole reason this is not `characteristics.manaValue`.** "While a spell … is on the
 * stack, the value of X is the value chosen or determined for it. In every other zone, the value of X is
 * treated as zero." So a Kaervek's Torch cast for X = 5 is a mana value 6 spell here and a mana value 1
 * card in every other zone, and only the object on the stack knows which — the announced value lives on
 * the cast record ([dev.mtgplay.core.state.StackEntry.Spell.chosenX]), not on the printed cost.
 *
 * Reading the printed cost instead would be right for every card in the gauntlet that has no `{X}` and
 * silently wrong for the two that do, which is exactly the shape of defect this engine treats as worse
 * than a crash: no error, a legal-looking answer, and a counter that catches a spell it should not.
 *
 * Fails loudly for a [Target] that is not a spell on the stack, and for one naming a spell that has left
 * it: the CR 608.2b re-check runs before any resolution, so a stale target has already fizzled whatever
 * pointed at it and can never reach here (ADR-005).
 */
fun spellManaValueOf(
    state: GameState,
    target: Target,
): Int {
    val spell =
        target as? Target.SpellOnStack
            ?: error("CR 202.3: a mana value read addresses a spell on the stack, got $target")
    return spellManaValue(state, spell.id)
}
