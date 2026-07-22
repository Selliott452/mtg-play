package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf

/*
 * The single read-through combat uses for a permanent's in-game characteristics.
 *
 * Combat asks these accessors — never a card definition directly — for the keywords, power, and
 * toughness it consults (CR 508–510). Today each accessor returns the *printed* value from the
 * object's definition (CR 613 layer 0). Phase 4's continuous-effect layer system replaces the
 * body of every accessor here with the layered computation (keyword grants in layer 6, P/T in
 * layers 7a–7d), and because combat only ever reads through this one seam, none of the combat
 * rules change when that lands. A card without a definition is inert: no keywords, and it is not
 * a creature — so it can never be a combatant.
 */

/** The battlefield object with [id]; fails loudly if it is not on the battlefield (CR 110.1). */
internal fun GameState.battlefieldObject(id: ObjectId): GameObject =
    sharedZones.battlefield.firstOrNull { it.id == id }
        ?: error("object $id is not on the battlefield")

/**
 * Whether the battlefield object [obj] is a creature right now (CR 302.1) — the P3.1 answer is
 * "its printed types include creature." An object with no definition is inert and not a creature.
 * Phase 4's layer system (type-changing effects, layer 4) reroutes through here.
 */
internal fun isCreature(
    state: GameState,
    obj: GameObject,
): Boolean {
    val characteristics = state.definitions[obj.card]?.characteristics ?: return false
    return CardType.CREATURE in characteristics.cardTypes
}

/**
 * The in-game keyword abilities of the battlefield object [id] (CR 702). Printed keywords for
 * now; Phase 4 (CR 613 layer 6) reroutes to include aura- and effect-granted keywords. An object
 * with no definition has none.
 */
internal fun effectiveKeywords(
    state: GameState,
    id: ObjectId,
): PersistentSet<Keyword> {
    val obj = state.battlefieldObject(id)
    return state.definitions[obj.card]?.characteristics?.keywords ?: persistentSetOf()
}

/**
 * The in-game power of the battlefield creature [id] (CR 208.1). Printed power for now; Phase 4
 * (CR 613 layers 7a–7d) reroutes to the layered value. Fails loudly on a non-creature — a
 * combatant is always a creature, so reaching this without a P/T box is an engine defect.
 */
internal fun effectivePower(
    state: GameState,
    id: ObjectId,
): Int = printedPowerToughness(state, id).power

/**
 * The in-game toughness of the battlefield creature [id] (CR 208.1). Printed toughness for now;
 * Phase 4 reroutes to the layered value. Fails loudly on a non-creature.
 */
internal fun effectiveToughness(
    state: GameState,
    id: ObjectId,
): Int = printedPowerToughness(state, id).toughness

private fun printedPowerToughness(
    state: GameState,
    id: ObjectId,
) = run {
    val obj = state.battlefieldObject(id)
    state.definitions[obj.card]?.characteristics?.powerToughness
        ?: error("CR 208.1: combatant ${obj.card.name} has no printed power/toughness; only creatures fight")
}
