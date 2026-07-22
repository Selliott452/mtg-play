package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import kotlinx.collections.immutable.PersistentSet

/*
 * The single read-through combat uses for a permanent's in-game characteristics.
 *
 * Combat asks these accessors — never a card definition directly — for the keywords, power, and
 * toughness it consults (CR 508–510). Each now delegates to the CR 613 continuous-effect layer
 * system ([layeredCharacteristics]): keyword grants in layer 6, P/T modifiers in sublayer 7c
 * (docs/design/layer-system.md §6). Because combat only ever reads through this one seam, no combat
 * rule changed when the layer engine landed — the P3.1 contract kept. A card without a definition is
 * inert: no keywords, and it is not a creature — so it can never be a combatant.
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
 * The in-game keyword abilities of the battlefield object [id] (CR 702, CR 613 layer 6): printed
 * keywords unioned with active aura/effect grants, via [layeredCharacteristics]. An object with no
 * definition has none.
 */
internal fun effectiveKeywords(
    state: GameState,
    id: ObjectId,
): PersistentSet<Keyword> = layeredCharacteristics(state, id).keywords

/**
 * The in-game power of the battlefield creature [id] (CR 208.1, CR 613 sublayer 7c), via
 * [layeredCharacteristics]. Fails loudly on a non-creature — a combatant is always a creature, so
 * reaching this without a P/T box is an engine defect.
 */
internal fun effectivePower(
    state: GameState,
    id: ObjectId,
): Int = layeredPower(state, id)

/**
 * The in-game toughness of the battlefield creature [id] (CR 208.1, CR 613 sublayer 7c), via
 * [layeredCharacteristics]. Fails loudly on a non-creature.
 */
internal fun effectiveToughness(
    state: GameState,
    id: ObjectId,
): Int = layeredToughness(state, id)
