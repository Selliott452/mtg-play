package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.EnchantRestriction
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState

/*
 * Interpreting an Aura's enchant restriction (CR 303.4a): whether a candidate battlefield object may
 * be enchanted by an Aura whose enchant ability carries a given [EnchantRestriction]. Reads printed
 * types and subtypes (no type-changing effect exists in the MVP pool, so no layer walk is needed —
 * docs/design/layer-system.md §6) plus control, which is ownership for now (§4). The same predicate
 * defines cast-time legal targets (CR 601.2c), the CR 608.2b resolution re-check, and the CR 704.5m
 * fall-off check, so those three can never drift apart.
 */

/** The Forest land subtype (CR 205.3) an "enchant Forest" Aura requires (CR 303.4a). */
private val FOREST_SUBTYPE = Subtype("Forest")

/**
 * Whether [candidate] satisfies [restriction] for an Aura controlled by [controller] (CR 303.4a).
 * "You control" is ownership in the MVP pool (docs/design/layer-system.md §4). Exhaustive over
 * [EnchantRestriction] so a new restriction breaks compilation rather than being silently ignored.
 */
internal fun satisfiesEnchantRestriction(
    state: GameState,
    restriction: EnchantRestriction,
    candidate: GameObject,
    controller: PlayerId,
): Boolean {
    val characteristics = state.definitions[candidate.card]?.characteristics ?: return false
    return when (restriction) {
        EnchantRestriction.CREATURE -> CardType.CREATURE in characteristics.cardTypes
        EnchantRestriction.LAND -> CardType.LAND in characteristics.cardTypes
        EnchantRestriction.FOREST -> FOREST_SUBTYPE in characteristics.subtypes
        EnchantRestriction.CREATURE_YOU_CONTROL ->
            CardType.CREATURE in characteristics.cardTypes && candidate.owner == controller
    }
}

/**
 * The enchant restriction of the battlefield object [obj] if it is an Aura (a permanent whose
 * enchant ability is a [TargetSpec.Enchantable]), or `null` if it is not — the signal the CR 704.5m
 * fall-off check uses to find Auras. A definitionless object is never an Aura.
 *
 * A **modal** card is never an Aura here, and the early return is load-bearing rather than defensive: a
 * modal card has no single targeting line and its `targetSpec` throws (`FW-MODAL`), so this asks about
 * modality before it asks about enchant-ness. An Aura whose *enchant ability* were modal is not a card
 * shape that exists — modality is chosen on the stack (CR 601.2b) while this reads a permanent already
 * on the battlefield — so "not an Aura" is the honest answer rather than a dodge.
 */
internal fun enchantRestrictionOf(
    state: GameState,
    obj: GameObject,
): EnchantRestriction? =
    (state.definitions[obj.card] as? SpellDefinition)
        // CR 601.2b: modality is asked *before* enchant-ness, because a modal card's `targetSpec` throws.
        ?.takeIf { it.modes.isEmpty() }
        ?.let { it.targetSpec as? TargetSpec.Enchantable }
        ?.restriction
