package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.definition.GraveyardCardRestriction
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState

/*
 * Interpreting a "target <kind of> card from a graveyard" restriction (CR 115.1, CR 404): whether a card
 * in a graveyard is a legal choice for a spec carrying a [GraveyardCardRestriction].
 *
 * The graveyard sibling of [satisfiesPermanentRestriction], [satisfiesEnchantRestriction] and
 * [satisfiesSpellRestriction], split from all three for the reason they are split from each other: they
 * answer about different kinds of object. Consulted by the one enumeration in `Targets.kt`, so cast-time
 * legality (CR 601.2c), the CR 603.3d trigger-placement choice, the CR 608.2b resolution re-check, and
 * the option list an agent sees (ADR-005) are the same predicate by construction.
 *
 * **Printed characteristics, deliberately and permanently.** The battlefield sibling reads *effective*
 * characteristics because CR 613 applies to a permanent; a card in a graveyard has only its printed ones
 * (CR 109.3), so there is no layer seam to leave open here and none is left. An undefined card ref is
 * inert (the P2.1 ruling) and satisfies nothing, which keeps it out of every option list rather than
 * crashing the enumerator.
 */

/**
 * Whether the graveyard object [candidate] satisfies [restriction] (CR 115.1, CR 205.2). Exhaustive over
 * [GraveyardCardRestriction] so a new restriction breaks compilation rather than being silently ignored.
 */
internal fun satisfiesGraveyardCardRestriction(
    state: GameState,
    restriction: GraveyardCardRestriction,
    candidate: GameObject,
): Boolean {
    val cardTypes = state.definitions[candidate.card]?.characteristics?.cardTypes ?: return false
    // CR 205.2: a card has a *set* of types, so each of these is membership, and an artifact creature
    // card satisfies CREATURE_OR_LAND exactly as a plain creature card does.
    return when (restriction) {
        GraveyardCardRestriction.INSTANT_OR_SORCERY ->
            CardType.INSTANT in cardTypes || CardType.SORCERY in cardTypes
        GraveyardCardRestriction.CREATURE_OR_LAND ->
            CardType.CREATURE in cardTypes || CardType.LAND in cardTypes
        GraveyardCardRestriction.CREATURE -> CardType.CREATURE in cardTypes
        // CR 115.1: "target card" names no type, so every defined card in an admitted graveyard
        // qualifies. The `?: return false` above still excludes an inert card — an object the engine
        // has no definition for is not a thing it can know is a card (the P2.1 ruling).
        GraveyardCardRestriction.ANY_CARD -> true
    }
}
