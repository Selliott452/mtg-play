package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.Quality
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.cardObject

/*
 * Protection (CR 702.16), the rules half of the framework docs/design/protection.md §4 splits in
 * two: `mtg-core`'s [Quality] states *which* quality an ability is protection from, and this file
 * owns the one predicate that says whether a given source *has* it.
 *
 * Protection is **quality-relative, not controller-relative**, and that is its single biggest shape
 * difference from hexproof. Hexproof needs one extra input — who is deciding. Every protection
 * check needs the *other object* and its characteristics: the spell being cast, the Aura, the
 * blocker, the damage source. A player may not target their own creature that has protection from
 * white with their own white spell.
 */

/**
 * Whether the object printed as [card] has the protection [quality] — the CR 702.16a test, and the
 * only place in the engine that answers it.
 *
 * Colours are read from [dev.mtgplay.core.card.PrintedCharacteristics.colors], which derives them
 * from the mana cost (CR 202.2). That is correct for every card in the gauntlet pool and has two
 * recorded blind spots, neither silently reachable: colour indicators (CR 204) are unmodelled and
 * flagged as such on the characteristics themselves, and a **layer-5 colour-changing effect**
 * (CR 613.1e) would make the printed colours the wrong ones to read. Layer 5 is an ordered stage the
 * layer engine keeps empty behind a loud gate (docs/design/layer-system.md §1), so the day a
 * colour-changing effect exists this read must move to layered colour — and the gate makes that
 * impossible to miss rather than a silent divergence.
 *
 * An unregistered [card] is colourless, and therefore has no quality: a source with no definition
 * cannot be monocolored and cannot be any colour.
 */
internal fun sourceHasQuality(
    state: GameState,
    card: CardRef,
    quality: Quality,
): Boolean {
    val colors: Set<Color> = state.definitions[card]?.characteristics?.colors ?: emptySet()
    return when (quality) {
        // CR 702.16a: the usual case — the source is that colour.
        is Quality.OfColor -> quality.color in colors
        // Guardian of the Guildpact: exactly one colour. CR 105.4 — colourless is the absence of
        // colour, not a sixth colour — so a colourless source is not monocolored, and neither is a
        // multicolored one. That blind spot is the printed card's, faithfully.
        Quality.Monocolored -> colors.size == 1
    }
}

/**
 * Whether the battlefield object [protectedId] has protection from *any* quality the object printed
 * as [sourceCard] has (CR 702.16) — the shared predicate behind all four letters of DEBT.
 *
 * Reads **effective** protections ([effectiveProtections]), so an Aura-granted protection restricts
 * exactly as a printed one does (CR 613 layer 6), and CR 702.16m's redundancy of repeated instances
 * is the set's own doing.
 */
internal fun hasProtectionFrom(
    state: GameState,
    protectedId: ObjectId,
    sourceCard: CardRef,
): Boolean = effectiveProtections(state, protectedId).any { sourceHasQuality(state, sourceCard, it) }

/**
 * The printed identity of the object [id], wherever it currently is — the lookup CR 702.16b needs
 * for a *prospective* source, which is not yet on the battlefield and not yet on the stack.
 *
 * A spell being cast is still in its caster's hand (or graveyard, for flashback) while the engine
 * enumerates its legal targets at CR 601.2c, and is on the stack by the CR 608.2b re-check. Both
 * must produce the same answer or cast-time and resolution-time legality drift — the one thing
 * ADR-005's "legality is defined by the enumeration" exists to prevent — so the search spans every
 * zone rather than assuming one. `null` when no object anywhere carries the id.
 */
internal fun printedIdentityOf(
    state: GameState,
    id: ObjectId,
): CardRef? {
    val everywhere =
        sequence {
            yieldAll(state.sharedZones.battlefield)
            yieldAll(state.sharedZones.stack.mapNotNull { it.cardObject })
            yieldAll(state.sharedZones.exile)
            for (playerState in state.players.values) {
                yieldAll(playerState.hand)
                yieldAll(playerState.graveyard)
                yieldAll(playerState.library)
            }
        }
    return everywhere.firstOrNull { it.id == id }?.card
}
