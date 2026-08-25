package dev.mtgplay.rules.effect

import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.engine.colorsOfTarget

/**
 * Effect predicate: whether the object [target] names **is** [color] right now (CR 105, CR 202.2) — the
 * published test a "… *if it's red*" conditional effect composes (ADR-003; Hydroblast and Pyroblast are
 * the first clients, `FW-MODAL`).
 *
 * **This is the effect-side half of the pair whose confusion docs/design/countering-spells.md §1.2 exists
 * to prevent.** Its target-side twins are [dev.mtgplay.core.definition.SpellRestriction.OfColor] and
 * [dev.mtgplay.core.definition.PermanentRestriction]'s colour members,
 * which decide what may be *targeted*: an object failing those is never offered (ADR-005) and one that
 * stops satisfying them fizzles the spell (CR 608.2b). This function decides nothing about targeting at
 * all. A spell whose colour test lives here targets anything its spec admits, resolves, and then does
 * nothing if the test fails (CR 608.2c) — so Pyroblast is enumerable against a white spell, and
 * *must* be, because casting it is legal.
 *
 * Consulted at **resolution**, not at cast time, and the timing is observable: an object that changes
 * colour between the two is judged on what it is when the effect runs.
 *
 * Fails loudly on a target that is not an object with characteristics — a player (CR 115.1a) or a
 * graveyard card is not something a colour-conditional effect in this pool ever names, so reaching here
 * with one means the card wired a spec its resolution cannot answer (ADR-005). A target naming an object
 * that has already left its zone answers `false`: it no longer exists, so it is not [color].
 */
fun targetIsColor(
    state: GameState,
    target: Target,
    color: Color,
): Boolean = color in colorsOfTarget(state, target)
