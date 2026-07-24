package dev.mtgplay.cli

import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.pauper.DeckLoader
import dev.mtgplay.pauper.MvpCardPool
import dev.mtgplay.pauper.MvpDecks
import dev.mtgplay.rules.MatchConfig

/**
 * The started match plus its seat display names (P6.4): the Mono-Red Madness vs GW Bogles config the
 * MVP milestone plays, built from the pinned decklists and the full [MvpCards] definitions.
 *
 * @property config the match to start (ADR-006); starting player is seed-determined.
 * @property names each seat's deck name, for rendering.
 */
data class MatchSetup(
    val config: MatchConfig,
    val names: Map<PlayerId, String>,
)

/** Seat 0 plays Mono-Red Madness. */
val MADNESS_SEAT: PlayerId = PlayerId(0)

/** Seat 1 plays GW Bogles. */
val BOGLES_SEAT: PlayerId = PlayerId(1)

/**
 * Builds the MVP matchup: seat 0 Mono-Red Madness, seat 1 GW Bogles, over the full [MvpCards]
 * definitions, at [seed]. [mulligans] toggles the pre-game London-mulligan phase (on by default).
 */
fun buildMvpMatch(
    seed: Long,
    mulligans: Boolean = true,
): MatchSetup {
    val loader = DeckLoader(MvpCardPool.catalog)
    val madness = loader.load(MvpDecks.monoRedMadness).mainLibrary()
    val bogles = loader.load(MvpDecks.gwBogles).mainLibrary()
    val config =
        MatchConfig(
            seed = seed,
            libraries = mapOf(MADNESS_SEAT to madness, BOGLES_SEAT to bogles),
            definitions = MvpCards.definitions,
            startingPlayer = null,
            mulligansEnabled = mulligans,
        )
    val names = mapOf(MADNESS_SEAT to MvpDecks.monoRedMadness.name, BOGLES_SEAT to MvpDecks.gwBogles.name)
    return MatchSetup(config, names)
}
