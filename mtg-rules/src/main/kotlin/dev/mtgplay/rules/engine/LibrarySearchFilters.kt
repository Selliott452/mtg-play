package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Supertype
import dev.mtgplay.core.definition.LibrarySearchFilter
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState

/*
 * Which library cards a CR 701.18 search may find — the rules-side reading of
 * [LibrarySearchFilter]'s two axes, in the shape `GraveyardCardRestrictions.kt` and
 * `PermanentRestrictions.kt` already use for their own declarative filters (ADR-009: core declares,
 * rules decides).
 */

/** The library cards of [player] matching [filter] (CR 701.18), in library (top-first) order. */
internal fun matchingLibraryCards(
    state: GameState,
    player: PlayerId,
    filter: LibrarySearchFilter,
): List<GameObject> = state.player(player).library.filter { matchesSearchFilter(state, it, filter) }

/**
 * Whether the library [obj] matches the search [filter] (CR 701.18) — read from its **printed**
 * characteristics, which is all a card outside the battlefield and the stack has (CR 109.3). No layer
 * system reaches a library card, so this reads printed types and always will; that is the same ruling
 * `GraveyardCardRestriction` records for a graveyard card, and for the same reason.
 *
 * Three conjuncts, one per axis:
 * - the **card type** (CR 205.2, CR 305) is demanded unconditionally, because every search the gauntlet
 *   prints is a land search — see [LibrarySearchFilter];
 * - the **Basic supertype** (CR 205.4, CR 305.6) only when the filter asks for it;
 * - a **land type** (CR 205.3b) only when the filter names any, and any one of them suffices —
 *   typecycling names a subtype, never the basic land (CR 702.28b), so a nonbasic land with the type is
 *   an equally legal find.
 *
 * An **undefined** card (P2.1's inert card) matches nothing: it has no characteristics to read, so it is
 * not a land card and can never be a legal find.
 */
private fun matchesSearchFilter(
    state: GameState,
    obj: GameObject,
    filter: LibrarySearchFilter,
): Boolean {
    val characteristics = state.definitions[obj.card]?.characteristics ?: return false
    val isLand = CardType.LAND in characteristics.cardTypes
    val basicEnough = !filter.basic || Supertype.BASIC in characteristics.supertypes
    val typedEnough = filter.landTypes.isEmpty() || filter.landTypes.any { characteristics.hasSubtype(it) }
    return isLand && basicEnough && typedEnough
}
