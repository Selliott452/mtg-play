package dev.mtgplay.cli

import dev.mtgplay.pauper.MvpCardPool
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The scripted-I/O end-to-end smoke (P6.4 deliverable 7): a real Mono-Red Madness vs GW Bogles game,
 * driven entirely through a scripted [CliIo] with no real stdin, reaches [dev.mtgplay.rules.AdvanceResult.GameOver]
 * deterministically. The human plays every decision with the safe default (Enter/pass); the opponent
 * is the seeded random-legal chooser, so the whole game is a `(seed, decisions)` replay (ADR-006).
 */
class CliSmokeSpec :
    StringSpec({
        "a full vs-random game reaches game over and prints the replay recipe" {
            val seed = 20260722L
            val setup = buildMvpMatch(seed = seed, mulligans = true)
            val options = CliOptions(seed = seed, humanSeat = MADNESS_SEAT, vsRandom = true, mulligans = true)
            val io = ScriptedCliIo(inputs = emptyList())

            val result = CliDriver(io, setup, options).run()

            // A real result: the two seats are the winner and loser (CR 104.2a).
            (result.winner == MADNESS_SEAT || result.winner == BOGLES_SEAT).shouldBeTrue()
            result.winner shouldBe (if (result.loser == MADNESS_SEAT) BOGLES_SEAT else MADNESS_SEAT)

            val transcript = io.transcript()
            transcript shouldContain MvpCardPool.catalog.attribution
            transcript shouldContain "Seed: $seed"
            transcript shouldContain "Turn 1"
            transcript shouldContain "GAME OVER"
            transcript shouldContain "Replay recipe: seed $seed"
        }

        "the same seed replays to the identical result and decision count (ADR-006)" {
            val seed = 777L

            fun play(): Pair<Int, String> {
                val setup = buildMvpMatch(seed = seed, mulligans = true)
                val options = CliOptions(seed = seed, humanSeat = MADNESS_SEAT, vsRandom = true, mulligans = true)
                val io = ScriptedCliIo()
                val result = CliDriver(io, setup, options).run()
                val recipeLine = io.output.first { it.startsWith("Replay recipe") }
                return result.winner.seat to recipeLine
            }
            play() shouldBe play()
        }

        "a hotseat game (both seats default to pass) decks out to game over" {
            val seed = 42L
            val setup = buildMvpMatch(seed = seed, mulligans = false)
            val options = CliOptions(seed = seed, humanSeat = MADNESS_SEAT, vsRandom = false, mulligans = false)
            val io = ScriptedCliIo()

            val result = CliDriver(io, setup, options).run()

            // Neither seat ever plays anything, so the game ends as a deck-out loss (CR 704.5c).
            io.transcript() shouldContain "GAME OVER"
            result.winner.seat shouldBeGreaterThan -1
        }
    })
