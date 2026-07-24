package dev.mtgplay.server

import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.pauper.DeckList
import dev.mtgplay.pauper.DeckLoader
import dev.mtgplay.pauper.MvpCardPool
import dev.mtgplay.pauper.MvpDecks
import dev.mtgplay.rules.MatchConfig

/**
 * The reference server's default matchup (the MVP milestone): Mono-Red Madness (seat 0) vs GW Bogles
 * (seat 1) over the full [MvpCards] definitions, mulligans on (the [MatchConfig] default), starting
 * player seed-determined (ADR-006). This is the config the server main hosts, and it exercises the
 * whole schema surface a schema-speaking client must handle.
 */
object MvpMatch {
    /** The Mono-Red Madness seat (docs/decklists.md). */
    val monoRedSeat: PlayerId = PlayerId(0)

    /** The GW Bogles seat (docs/decklists.md). */
    val boglesSeat: PlayerId = PlayerId(1)

    /** The MVP matchup [MatchConfig] for [seed] (ADR-006); reproducible from the seed alone. */
    fun config(seed: Long): MatchConfig =
        MatchConfig(
            seed = seed,
            libraries =
                mapOf(
                    monoRedSeat to library(MvpDecks.monoRedMadness),
                    boglesSeat to library(MvpDecks.gwBogles),
                ),
            definitions = MvpCards.definitions,
            startingPlayer = null,
        )

    private fun library(deck: DeckList) = DeckLoader(MvpCardPool.catalog).load(deck).mainLibrary()
}
