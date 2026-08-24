package dev.mtgplay.cards

import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.identity.CardRef

/**
 * The registry of every card `mtg-cards` defines so far. The P2.2 pool: the basic lands
 * [mountain], [forest], and [plains], and [lightningBolt]; the gauntlet Tranche 0 packet completes
 * the five basics with [island] and [swamp]. The P3.2 pool adds the first real
 * creatures (CR 302): [grizzlyBears], [hillGiant], [windDrake], [youthfulKnight], and
 * [standingTroops]. The P4.2 pool adds the seven real Bogles continuous-effect Auras (CR 303):
 * [rancor], [armadilloCloak], [cartoucheOfSolidarity], [sentinelsEyes], [etherealArmor],
 * [ancestralMask], and [abundantGrowth] — each encoded to its P4 static half (docs/design/layer-system.md).
 * The P5.3 pool adds the three real Bogles hexproof one-drops (CR 702.11): [gladecoverScout],
 * [slipperyBogle] (the first hybrid-cost card), and [silhanaLedgewalker] (with its blockable-only-by-flying
 * evasion). The P6.2b pool completes both MVP decklists: the Mono-Red Madness thirteen — [guttersnipe],
 * [sneakySnacker], [voldarenEpicure], [fieryTemper], [fireblast], [lavaDart], [grabThePrize],
 * [faithlessLooting], [meldedMoxite], and [highwayRobbery] (MadnessDeck.kt) — plus the remaining GW-Bogles
 * utility cards [utopiaSprawl], [malevolentRumble], and [ashBarrens] (BoglesUtility.kt). Their predefined
 * tokens ([bloodToken], [robotToken], [eldraziSpawnToken]) are created on demand and registered by the
 * create-token primitive, so — like [warriorToken] — they are not top-level registry entries. The P6.3
 * pool refreshes both decklists as they are now built: [kessigFlamebreather] for Mono-Red Madness, and
 * [kruphixsInsight], [wildGrowth], and [lifelink] — the *card* named Lifelink, an Aura — for GW Bogles.
 * The card-selection packet adds the gauntlet's look-at-the-top-and-draw family (CardSelection.kt):
 * [thoughtScour] and [mentalNote] (the mill cantrips), [lorienRevealed] (draw three, or islandcycle),
 * [unfathomableTruths] (the devoid draw-three plus an Eldrazi Spawn), and [pursueThePast] (gain two,
 * loot, flash back). Its unencoded siblings — Brainstorm, Ponder, Preordain, Impulse, Winding Way, Lead
 * the Stampede — each need a library-ordering, scry, or choose-a-card-type decision the engine cannot
 * yet enumerate (ADR-005), so they are deliberately absent rather than approximated.
 *
 * [definitions] is shaped for direct `MatchConfig.definitions` consumption: the engine carries
 * it into `GameState` in canonical name-sorted order regardless of this map's own order
 * (ADR-009 — definitions ride in the state; a [CardRef] without an entry is inert). The pool
 * grows card by card through Phase 6; each addition is a definition file plus one entry here.
 */
object MvpCards {
    /** Every defined card, keyed by its printed-name [CardRef] (CR 201). */
    val definitions: Map<CardRef, CardDefinition> =
        listOf(
            abundantGrowth,
            ancestralMask,
            armadilloCloak,
            ashBarrens,
            cartoucheOfSolidarity,
            etherealArmor,
            faithlessLooting,
            fieryTemper,
            fireblast,
            forest,
            gladecoverScout,
            grabThePrize,
            grizzlyBears,
            guttersnipe,
            highwayRobbery,
            hillGiant,
            island,
            kessigFlamebreather,
            kruphixsInsight,
            lavaDart,
            lifelink,
            lightningBolt,
            lorienRevealed,
            malevolentRumble,
            meldedMoxite,
            mentalNote,
            mountain,
            plains,
            pursueThePast,
            rancor,
            sentinelsEyes,
            silhanaLedgewalker,
            slipperyBogle,
            sneakySnacker,
            standingTroops,
            swamp,
            thoughtScour,
            unfathomableTruths,
            utopiaSprawl,
            voldarenEpicure,
            wildGrowth,
            windDrake,
            youthfulKnight,
        ).associateBy { CardRef(it.characteristics.name) }
}
