package dev.mtgplay.cards

import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.identity.CardRef

/**
 * The registry of every card `mtg-cards` defines so far — the P2.2 pool: the basic lands
 * [mountain], [forest], and [plains], and [lightningBolt].
 *
 * [definitions] is shaped for direct `MatchConfig.definitions` consumption: the engine carries
 * it into `GameState` in canonical name-sorted order regardless of this map's own order
 * (ADR-009 — definitions ride in the state; a [CardRef] without an entry is inert). The pool
 * grows card by card through Phase 6; each addition is a definition file plus one entry here.
 */
object MvpCards {
    /** Every defined card, keyed by its printed-name [CardRef] (CR 201). */
    val definitions: Map<CardRef, CardDefinition> =
        listOf(forest, lightningBolt, mountain, plains)
            .associateBy { CardRef(it.characteristics.name) }
}
