package dev.mtgplay.acceptance

import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.pauper.DeckLoader
import dev.mtgplay.pauper.MvpCardPool
import dev.mtgplay.pauper.MvpDecks
import dev.mtgplay.rules.MatchConfig

/*
 * Shared support for the real-matchup suites: the Mono-Red-Madness-vs-GW-Bogles config over the completed
 * [MvpCards] pool. As of P6.2c both decks are fully playable by a pure random-legal responder — the four
 * architect gaps (Blood's activated ability, Highway Robbery's cost-then-draw, Faithless Looting's
 * resolution discard, Ash Barrens' search) are closed — so the gap-avoiding responder is retired and the
 * ignition/mulligan suites drive `RandomLegalResponder` over both real decks with no routing around any card
 * action.
 */

/** The Mono-Red Madness main library over [MvpCards] (all 60 mainboard cards defined). */
internal fun monoRedMadnessLibrary(): List<CardRef> =
    DeckLoader(MvpCardPool.catalog).load(MvpDecks.monoRedMadness).mainLibrary()

/** The GW Bogles main library over [MvpCards] (all 60 mainboard cards defined). */
internal fun gwBoglesLibrary(): List<CardRef> = DeckLoader(MvpCardPool.catalog).load(MvpDecks.gwBogles).mainLibrary()

/**
 * The real MVP matchup config: Mono-Red Madness ([alice]) vs GW Bogles ([bob]) over the full [MvpCards]
 * definitions, mulligans on (the default), seed-determined starting player (ADR-006).
 */
internal fun mvpMatchupConfig(seed: Long): MatchConfig =
    MatchConfig(
        seed = seed,
        libraries = mapOf(alice to monoRedMadnessLibrary(), bob to gwBoglesLibrary()),
        definitions = MvpCards.definitions,
        startingPlayer = null,
    )
