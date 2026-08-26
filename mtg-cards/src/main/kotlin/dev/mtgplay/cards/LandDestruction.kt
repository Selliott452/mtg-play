package dev.mtgplay.cards

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.definition.AdditionalCost
import dev.mtgplay.core.definition.LibrarySearch
import dev.mtgplay.core.definition.LibrarySearchDestination
import dev.mtgplay.core.definition.LibrarySearchFilter
import dev.mtgplay.core.definition.LibrarySearchSearcher
import dev.mtgplay.core.definition.PermanentRestriction
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.SacrificeFilter
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.definition.TimingClass
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.rules.effect.destroy
import kotlinx.collections.immutable.persistentSetOf

/*
 * Land destruction (CR 305), the gauntlet's Spy Combo and Jund Wildfire sideboard plan — built on
 * [PermanentRestriction.LAND], the target noun this packet adds.
 *
 * [raze] shipped first and [cleansingWildfire] joins it in `W9-F`. This file used to record three
 * blockers on Cleansing Wildfire; re-checked against the code as it now stands, only **two** were still
 * real:
 *
 * - **The searcher is the target's controller, not the spell's** — real, and the axis `W9-F` adds.
 *   [LibrarySearchSearcher] names the two readings and `engine/LibrarySearch.kt` derives the deciding
 *   seat from it; everything downstream of that seat (the pending record, the request id, the option
 *   list, the move, the shuffle, the seat view, the pause invariant) was already decider-generic and
 *   needed nothing at all.
 * - **A declined "may search" must not shuffle** — **stale**. `W8-E` landed [LibrarySearch.optional]
 *   with its own enumerated decline index for Gatecreeper Vine, and `applyLibrarySearchChoice` already
 *   suppresses the shuffle on exactly that index. Nothing here rebuilt it.
 * - **The draw comes after the search** — real, and fixed as a *clause tail* ([LibrarySearch.thenDraw])
 *   rather than a fold into the resolution effect, because a clause runs after the effect and nothing
 *   runs after a clause: a draw written into the effect would happen before the shuffle. The sibling of
 *   [dev.mtgplay.core.definition.LibraryLook.thenDraw], with one deliberate difference — it draws for
 *   the *controller*, and here the searcher is somebody else.
 */

/** The one card Cleansing Wildfire's last sentence draws (CR 121.1). */
private const val CLEANSING_WILDFIRE_DRAW: Int = 1

/**
 * Raze — `{R}` Sorcery. "As an additional cost to cast this spell, sacrifice a land. / Destroy target
 * land."
 *
 * Two rules stages, and the order between them is the whole card. CR 601.2b announces the additional
 * cost and CR 601.2c chooses the target, but neither is *paid* until CR 601.2h — so the land being
 * sacrificed is **still on the battlefield** while the target is chosen, and is therefore itself a
 * legal target. "Raze targeting the land it sacrifices" is a legal (if pointless) play, and the
 * engine offers it because CR 601.2c says it must; the two are different instances and the CR 601.2c
 * same-object rule does not span a cost and a target.
 *
 * The additional cost is [AdditionalCost.Sacrifice] over `{LAND}` — the shape Crop Rotation already
 * uses — which means the enumeration reserves the sacrificed land from funding the `{R}` only where it
 * must (docs/design/mana-payment.md §2.2). Tapping a Mountain for the `{R}` and *then* sacrificing
 * that same Mountain is legal and enumerated, because the mana is paid before the sacrifice.
 *
 * A one-mana Stone Rain that costs a land is a losing trade on rate and a winning one on tempo, which
 * is why it appears in the gauntlet only where a land is already a resource to be spent.
 */
val raze: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Raze",
                manaCost = ManaCost.parse("{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED

        // CR 601.2b: the additional cost, announced with the spell and paid at CR 601.2h — after the
        // target is chosen, which is what keeps the sacrificed land targetable by the spell paying for it.
        override val additionalCost =
            AdditionalCost.Sacrifice(count = 1, filter = SacrificeFilter(persistentSetOf(CardType.LAND)))

        // CR 115.1b with CR 305: any land, whoever controls it — including the caster's own.
        override val targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.LAND)
        override val resolution =
            ResolutionEffect { state, context -> destroy(state, targetedPermanent(context.targets, "Raze")) }
    }

/**
 * Cleansing Wildfire — `{1}{R}` Sorcery. "Destroy target land. Its controller may search their library
 * for a basic land card, put it onto the battlefield tapped, then shuffle. / Draw a card."
 *
 * A Stone Rain that replaces itself and hands its victim a Rampant Growth, which is why the gauntlet
 * plays it as *ramp and a cantrip* aimed at one's own Bridge land rather than as land destruction. Four
 * readings of the printed text decide whether the encoding is the card:
 *
 * - **"Its controller", not "you".** The searching seat is the destroyed land's controller. Aimed at an
 *   opponent's land the card is a Stone Rain that apologises; aimed at your own it is a two-mana
 *   Rampant Growth that draws. Encoding it with the caster always searching would delete the first
 *   reading and turn the card into a strictly better one (PLAN.md §7), so
 *   [LibrarySearchSearcher.TARGET_CONTROLLER] is not a cosmetic axis.
 * - **The controller is read as CR 608.2h last-known information.** By the time the search clause runs,
 *   this spell's own effect has destroyed the land and the permanent is a new object in a graveyard
 *   (CR 400.7). CR 608.2h settles "its controller" once, as the effect is applied, so the engine reads
 *   it from the board as the spell *began* resolving rather than from the battlefield it just emptied.
 * - **"May search", and declining must not shuffle.** A seat that has just stacked its top cards with a
 *   Brainstorm may decline, keep the order, and take the land loss; a seat that searches and fails to
 *   find still shuffles. Those are different enumerated indices (CR 601.3b against CR 701.18b), and the
 *   shuffle consumes seeded entropy (ADR-006), so the difference survives into replay.
 * - **The draw is the last sentence, and it is the caster's.** It happens after the search and after its
 *   shuffle, which is why it is a clause tail rather than part of the resolution effect.
 *
 * If the targeted land is an illegal target as this resolves, the spell does not resolve at all
 * (CR 608.2b): no destroy, no search, and **no draw** — the draw is not a separate object.
 */
val cleansingWildfire: SpellDefinition =
    object : SpellDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = "Cleansing Wildfire",
                manaCost = ManaCost.parse("{1}{R}"),
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.SORCERY),
                subtypes = persistentSetOf(),
                powerToughness = null,
            )
        override val timing = TimingClass.SORCERY_SPEED

        // CR 115.1b with CR 305: any land, whoever controls it — including the caster's own Bridge.
        override val targetSpec = TargetSpec.TargetPermanent(PermanentRestriction.LAND)
        override val resolution =
            ResolutionEffect { state, context ->
                destroy(state, targetedPermanent(context.targets, "Cleansing Wildfire"))
            }

        // CR 608.2c: the search and the draw both come *after* the destroy, and they run in that order
        // because a clause runs after the ordinary effect (`FW-CLAUSEHOOK`).
        override val librarySearch =
            LibrarySearch(
                find = LibrarySearchFilter.BASIC_LAND_CARD,
                destination = LibrarySearchDestination.BATTLEFIELD_TAPPED,
                optional = true,
                searcher = LibrarySearchSearcher.TARGET_CONTROLLER,
                thenDraw = CLEANSING_WILDFIRE_DRAW,
            )
    }
