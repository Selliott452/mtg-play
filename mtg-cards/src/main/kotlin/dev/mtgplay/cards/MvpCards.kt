package dev.mtgplay.cards

import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.identity.CardRef

/**
 * The registry of every card `mtg-cards` defines so far. The P2.2 pool: the basic lands
 * [mountain], [forest], and [plains], and [lightningBolt]. The P3.2 pool adds the first real
 * creatures (CR 302): [grizzlyBears], [hillGiant], [windDrake], [youthfulKnight], and
 * [standingTroops]. The P4.2 pool adds the seven real Bogles continuous-effect Auras (CR 303):
 * [rancor], [armadilloCloak], [cartoucheOfSolidarity], [sentinelsEyes], [etherealArmor],
 * [ancestralMask], and [abundantGrowth] — each encoded to its P4 static half (docs/design/layer-system.md).
 * The P5.3 pool adds the three real Bogles hexproof one-drops (CR 702.11): [gladecoverScout],
 * [slipperyBogle] (the first hybrid-cost card), and [silhanaLedgewalker] (with its blockable-only-by-flying
 * evasion).
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
            cartoucheOfSolidarity,
            etherealArmor,
            forest,
            gladecoverScout,
            grizzlyBears,
            hillGiant,
            lightningBolt,
            mountain,
            plains,
            rancor,
            sentinelsEyes,
            silhanaLedgewalker,
            slipperyBogle,
            standingTroops,
            windDrake,
            youthfulKnight,
        ).associateBy { CardRef(it.characteristics.name) }
}
