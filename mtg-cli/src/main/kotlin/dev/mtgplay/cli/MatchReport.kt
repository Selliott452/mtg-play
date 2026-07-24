package dev.mtgplay.cli

import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.pauper.MvpCardPool
import dev.mtgplay.rules.MatchResult

/*
 * The startup banner and closing report (P6.4 deliverables 5 and 3): the Scryfall CC BY attribution
 * at startup, and the result plus the replay recipe (seed + decision count) at game end.
 */

/** Prints the startup banner: the title, the CC BY attribution (ADR-003), the seed, mode, and seats. */
fun printBanner(
    io: CliIo,
    setup: MatchSetup,
    options: CliOptions,
) {
    io.writeLine("mtg-play - MVP CLI: Mono-Red Madness vs GW Bogles")
    io.writeLine(MvpCardPool.catalog.attribution)
    val mode = if (options.vsRandom) "human vs seeded random-legal opponent" else "hotseat (both seats human)"
    io.writeLine("Seed: ${setup.config.seed}   Mode: $mode")
    io.writeLine("Seats: ${seatSummary(setup, options.humanSeats)}")
    io.writeLine("Press Enter to take the safe default (usually pass); type ? for help.")
    io.writeLine("")
}

/** A one-line summary of who controls each seat. */
private fun seatSummary(
    setup: MatchSetup,
    humanSeats: Set<PlayerId>,
): String =
    setup.names.entries.joinToString("; ") { (seat, name) ->
        val controller = if (seat in humanSeats) "human" else "random"
        "${seat.seat}=$name ($controller)"
    }

/** Prints the closing report: the winner, the loss reason, and the replay recipe (seed + count). */
fun printResult(
    io: CliIo,
    setup: MatchSetup,
    result: MatchResult,
    decisions: Int,
) {
    val winner = setup.names[result.winner] ?: "Player ${result.winner.seat}"
    val loser = setup.names[result.loser] ?: "Player ${result.loser.seat}"
    io.writeLine("")
    io.writeLine("======================= GAME OVER =======================")
    io.writeLine("$winner wins - $loser lost (${prettyName(result.reason.name)}).")
    io.writeLine("Replay recipe: seed ${setup.config.seed}, $decisions decision(s).")
}
