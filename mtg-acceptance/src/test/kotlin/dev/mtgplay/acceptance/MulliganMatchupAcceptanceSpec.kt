package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.RandomLegalResponder
import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.acceptance.replay.ReplayHarness
import dev.mtgplay.acceptance.replay.fingerprint
import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.MulliganStage
import dev.mtgplay.core.state.PendingMulligan
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.pauper.DeckLoader
import dev.mtgplay.pauper.DefinitionCoverage
import dev.mtgplay.pauper.MvpCardPool
import dev.mtgplay.pauper.MvpDecks
import dev.mtgplay.pauper.PauperValidator
import dev.mtgplay.rules.MatchConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The MVP matchup, end to end through the format layer and the pre-game mulligan phase (P6.1): the
 * two real decklists load and validate legal, their current definition gaps are acknowledged, and a
 * real Mono-Red-vs-Bogles game — mulligans included — runs green and replays exactly.
 */
class MulliganMatchupAcceptanceSpec :
    StringSpec({
        val loader = DeckLoader(MvpCardPool.catalog)
        val monoRed = loader.load(MvpDecks.monoRedMadness)
        val bogles = loader.load(MvpDecks.gwBogles)

        fun matchupConfig(seed: Long): MatchConfig =
            MatchConfig(
                seed = seed,
                libraries = mapOf(alice to monoRed.mainLibrary(), bob to bogles.mainLibrary()),
                definitions = MvpCards.definitions,
                startingPlayer = null,
                // Mulligans on (the default) — this is the mulligan-inclusive real-game path.
            )

        "P6.1: both MVP decklists load and validate as legal Pauper decks" {
            PauperValidator.validate(monoRed).isLegal.shouldBeTrue()
            PauperValidator.validate(bogles).isLegal.shouldBeTrue()
        }

        "P6.2 checklist: the current mainboard definition gaps are acknowledged" {
            DefinitionCoverage.check(monoRed).missingNames shouldBe
                listOf(
                    "Faithless Looting",
                    "Fiery Temper",
                    "Fireblast",
                    "Grab the Prize",
                    "Guttersnipe",
                    "Highway Robbery",
                    "Lava Dart",
                    "Melded Moxite",
                    "Sneaky Snacker",
                    "Voldaren Epicure",
                )
            DefinitionCoverage.check(bogles).missingNames shouldBe
                listOf("Ash Barrens", "Malevolent Rumble", "Utopia Sprawl")
        }

        "P6.1/ADR-006: mulligan-inclusive real games run green across the seed corpus and take mulligans" {
            var anyMulligan = false
            fuzzSeeds(default = MULLIGAN_CORPUS_SEEDS).forEach { seed ->
                val game =
                    ScriptedGame
                        .start(matchupConfig(seed))
                        .playToCompletion(RandomLegalResponder(seed), turnCap = REAL_CARD_TURN_CAP)
                game.isOver.shouldBeTrue()
                if (game.state.events.any { it is GameEvent.MulliganTaken }) anyMulligan = true
            }
            // The random responder mulligans some of the time, so the corpus exercises the real phase.
            anyMulligan.shouldBeTrue()
        }

        "ADR-006: a mulligan-inclusive game replays to an identical fingerprint and event log" {
            // The first corpus seed whose game actually takes a mulligan, so the replay covers the phase.
            val seed =
                (0L until MULLIGAN_CORPUS_SEEDS.toLong()).first { candidate ->
                    ScriptedGame
                        .start(matchupConfig(candidate))
                        .playToCompletion(RandomLegalResponder(candidate), turnCap = REAL_CARD_TURN_CAP)
                        .state.events
                        .any { it is GameEvent.MulliganTaken }
                }
            val original =
                ScriptedGame
                    .start(matchupConfig(seed))
                    .playToCompletion(RandomLegalResponder(seed), turnCap = REAL_CARD_TURN_CAP)
            ReplayHarness.verifyReproduces(matchupConfig(seed), original).reproduced.shouldBeTrue()
        }

        "the fingerprint digests the pending-mulligan phase (P6.1)" {
            val base =
                twoPlayerState(
                    turn = Turn(alice, 1, TurnPhase.BEGINNING, TurnStep.UNTAP),
                    aliceState = playerWithZones(library = mountains(0L..5L, alice)),
                    bobState = playerWithZones(library = mountains(10L..15L, bob)),
                    nextObjectId = 100,
                )
            val declaring = base.copy(pendingMulligan = PendingMulligan(alice, 0, MulliganStage.DECLARE))
            val afterOneMull = base.copy(pendingMulligan = PendingMulligan(alice, 1, MulliganStage.DECLARE))

            // Present vs absent, and differing mulligan counts, all fingerprint apart.
            fingerprint(declaring) shouldNotBe fingerprint(base)
            fingerprint(afterOneMull) shouldNotBe fingerprint(declaring)
        }

        "a game with mulligans off still starts straight into turn 1 (compatibility path)" {
            val config =
                MatchConfig(
                    seed = 1,
                    libraries =
                        mapOf(
                            alice to List(DECK_SIZE) { CardRef("Mountain") },
                            bob to List(DECK_SIZE) { CardRef("Mountain") },
                        ),
                    definitions = MvpCards.definitions,
                    startingPlayer = alice,
                    mulligansEnabled = false,
                )
            val game = ScriptedGame.start(config)
            game.state.pendingMulligan shouldBe null
            game.state.turn.number shouldBe 1
        }
    })

/** Seed count for the mulligan-inclusive matchup corpus; scaled by `-PfuzzSeeds` in nightly CI. */
private const val MULLIGAN_CORPUS_SEEDS = 6
