package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.Subtype
import dev.mtgplay.core.definition.AbilityCost
import dev.mtgplay.core.definition.AbilityZoneScope
import dev.mtgplay.core.definition.ActivatedAbility
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.definition.LibrarySearch
import dev.mtgplay.core.definition.LibrarySearchDestination
import dev.mtgplay.core.definition.LibrarySearchFilter
import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.rules.effect.drawCards
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/*
 * The Landscape cycle — the gauntlet's three-colour fixing lands (`P-SEARCH`,
 * docs/design/library-search.md). Each is the same card with a different colour triple:
 *
 *   {T}: Add {C}.
 *   {T}, Sacrifice this land: Search your library for a basic <A>, <B>, or <C> card, put it onto the
 *     battlefield tapped, then shuffle.
 *   Cycling {A}{B}{C}
 *
 * Two abilities that between them needed both halves of this packet. The **sacrifice** half is the
 * first search with a battlefield destination *and* the first with a multi-type filter — neither
 * expressible while `LibrarySearch` had a single `toHand` filter drawn from a three-member enum. The
 * **cycling** half is plain CR 702.29 cycling, which the published vocabulary already composed: a
 * hand-scoped activated ability ([AbilityZoneScope.Hand], CR 113.6c) whose cost is
 * [AbilityCost.Mana] + [AbilityCost.DiscardSelf] and whose effect draws a card. That composition was
 * reported as untested, and it is: Ash Barrens and Lórien Revealed print *typecycling*, whose effect is
 * a search, so no card in the pool had ever exercised plain cycling's draw. These three do.
 *
 * **Cycling is not a search and this file is where that stops being a coincidence.** CR 702.29a is
 * "discard this card: draw a card"; CR 702.29f's typecycling replaces the *draw* with a search. So the
 * two halves of a Landscape are two different abilities that happen to share a discard idiom, not one
 * ability with a mode — which is why the cycling ability carries no [ActivatedAbility.librarySearch] and
 * the sacrifice ability carries no draw.
 *
 * The `{T}: Add {C}` half is a real printed ability rather than reminder text (a Landscape has no land
 * type at all), so it is authored as a [ManaAbility] like every other land's.
 */

/** What a cycling ability draws (CR 702.29a) — one card. */
const val CYCLING_DRAW: Int = 1

/** The Plains land type (CR 205.3b), as the Landscapes name it. */
private val PLAINS = LibrarySearchFilter.PLAINS

/** The Island land type (CR 205.3b). */
private val ISLAND = LibrarySearchFilter.ISLAND

/** The Swamp land type (CR 205.3b). */
private val SWAMP = LibrarySearchFilter.SWAMP

/** The Mountain land type (CR 205.3b). */
private val MOUNTAIN = LibrarySearchFilter.MOUNTAIN

/** The Forest land type (CR 205.3b). */
private val FOREST = LibrarySearchFilter.FOREST

/**
 * The cycling ability of a Landscape (CR 702.29a): "[cyclingCost], Discard this card: Draw a card."
 *
 * A hand-functioning activated ability (CR 113.6c, CR 602) whose composite cost is the mana plus
 * discarding the card itself, and whose whole effect is the draw. Nothing about cycling is a search —
 * see this file's header — so this ability declares no clause at all.
 */
private fun cycling(cyclingCost: String): ActivatedAbility =
    ActivatedAbility(
        cost = persistentListOf(AbilityCost.Mana(ManaCost.parse(cyclingCost)), AbilityCost.DiscardSelf),
        effect = ResolutionEffect { state, context -> drawCards(state, context.controller, CYCLING_DRAW) },
        zoneScope = AbilityZoneScope.Hand,
    )

/**
 * The fetch ability of a Landscape (CR 602, CR 701.18): "{T}, Sacrifice this land: Search your library
 * for a basic land card of one of [types], put it onto the battlefield tapped, then shuffle."
 *
 * Both cost components are paid on activation (CR 602.2b), so the land is already in the graveyard when
 * the search resolves — which is why the ordinary effect is a no-op and the whole resolution is the
 * [LibrarySearch] clause the engine orchestrates. It pauses for the find-one choice (failing to find is
 * always legal when searching your own library, CR 701.18b), puts the found basic onto the battlefield
 * **tapped** (CR 110.5b — the instruction fixes the status), and shuffles through the match PRNG
 * (ADR-006).
 *
 * No reveal, and the card does not print one: the battlefield is a public zone (CR 400.2), so the found
 * card is visible to everyone the moment it lands.
 */
private fun fetchBasic(types: Set<Subtype>): ActivatedAbility =
    ActivatedAbility(
        cost = persistentListOf(AbilityCost.TapSelf, AbilityCost.SacrificeSelf),
        effect = ResolutionEffect { state, _ -> state },
        librarySearch =
            LibrarySearch(
                find = LibrarySearchFilter.basicOneOf(types),
                destination = LibrarySearchDestination.BATTLEFIELD_TAPPED,
            ),
    )

/** The printed characteristics shared by every Landscape: a typeless, costless land (CR 305). */
private fun landscapeCharacteristics(name: String): PrintedCharacteristics =
    PrintedCharacteristics(
        name = name,
        manaCost = null,
        supertypes = persistentSetOf(),
        cardTypes = persistentSetOf(CardType.LAND),
        subtypes = persistentSetOf(),
        powerToughness = null,
    )

/** One Landscape: the colourless mana ability, the fetch ability for [types], and cycling for [cyclingCost]. */
private fun landscape(
    name: String,
    types: Set<Subtype>,
    cyclingCost: String,
): CardDefinition =
    object : CardDefinition {
        override val characteristics = landscapeCharacteristics(name)
        override val manaAbilities: PersistentList<ManaAbility> =
            persistentListOf(ManaAbility(persistentListOf(ManaType.COLORLESS)))
        override val activatedAbilities: PersistentList<ActivatedAbility> =
            persistentListOf(fetchBasic(types), cycling(cyclingCost))
    }

/**
 * Contaminated Landscape — Land. "{T}: Add {C}. {T}, Sacrifice this land: Search your library for a
 * basic Plains, Island, or Swamp card, put it onto the battlefield tapped, then shuffle.
 * Cycling {W}{U}{B}."
 *
 * UWX Familiar's Esper fixing. The cycling cost is three *coloured* pips, not a generic three: cycling
 * a Landscape is a late-game concession, and the fetch is the ability the deck actually plays it for.
 */
val contaminatedLandscape: CardDefinition =
    landscape("Contaminated Landscape", setOf(PLAINS, ISLAND, SWAMP), "{W}{U}{B}")

/**
 * Twisted Landscape — Land. "{T}: Add {C}. {T}, Sacrifice this land: Search your library for a basic
 * Swamp, Mountain, or Forest card, put it onto the battlefield tapped, then shuffle.
 * Cycling {B}{R}{G}."
 *
 * Jund Wildfire's Jund fixing; Contaminated Landscape with the other colour triple.
 */
val twistedLandscape: CardDefinition =
    landscape("Twisted Landscape", setOf(SWAMP, MOUNTAIN, FOREST), "{B}{R}{G}")

/**
 * Perilous Landscape — Land. "{T}: Add {C}. {T}, Sacrifice this land: Search your library for a basic
 * Island, Mountain, or Plains card, put it onto the battlefield tapped, then shuffle.
 * Cycling {U}{R}{W}."
 *
 * Jeskai Ephemerate's Jeskai fixing; the third member of the cycle.
 */
val perilousLandscape: CardDefinition =
    landscape("Perilous Landscape", setOf(ISLAND, MOUNTAIN, PLAINS), "{U}{R}{W}")
