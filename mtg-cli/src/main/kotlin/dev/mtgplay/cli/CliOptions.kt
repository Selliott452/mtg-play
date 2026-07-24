package dev.mtgplay.cli

import dev.mtgplay.core.identity.PlayerId

/**
 * The parsed command-line options (P6.4 deliverable 4).
 *
 * @property seed the match seed (ADR-006); a `--seed`-less run gets a printed [System.nanoTime]-based
 *   seed so the game is still reproducible via `--seed`.
 * @property humanSeat in vs-random mode, the seat the human plays; ignored in hotseat (both human).
 * @property vsRandom whether the opponent is the seeded random-legal chooser (hotseat when `false`).
 * @property mulligans whether the pre-game London-mulligan phase runs.
 */
data class CliOptions(
    val seed: Long,
    val humanSeat: PlayerId,
    val vsRandom: Boolean,
    val mulligans: Boolean,
) {
    /** The seats a human controls: both in hotseat, only [humanSeat] against the random opponent. */
    val humanSeats: Set<PlayerId>
        get() = if (vsRandom) setOf(humanSeat) else setOf(MADNESS_SEAT, BOGLES_SEAT)
}

/** The usage text printed for `--help` and on a bad argument. */
val USAGE: String =
    """
    mtg-play CLI - Mono-Red Madness vs GW Bogles (MVP milestone).

    Usage: mtg-cli [options]
      --seed <long>     match seed (default: a printed time-based seed)
      --seat <0|1>      in vs-random, the seat you play (default 0 = Mono-Red Madness)
      --vs-random       play a seeded random-legal opponent (default: hotseat, both human)
      --no-mulligans    skip the pre-game London-mulligan phase
      --help, -h        print this help
    """.trimIndent()

/**
 * Parses [args] into [CliOptions], throwing [IllegalArgumentException] with the usage on a bad flag,
 * a missing value, or an out-of-range seat. A seedless run draws its default seed from
 * [System.nanoTime] (not game randomness - the seed is a config input, printed for reproducibility).
 */
fun parseArgs(args: Array<String>): CliOptions {
    var seed: Long? = null
    var seat = 0
    var vsRandom = false
    var mulligans = true
    var index = 0
    while (index < args.size) {
        when (val arg = args[index]) {
            "--vs-random" -> vsRandom = true
            "--no-mulligans" -> mulligans = false
            "--help", "-h" -> throw IllegalArgumentException(USAGE)
            "--seed" -> {
                seed = valueAfter(args, index, "--seed").toLongOrNull() ?: fail("--seed needs a whole number")
                index++
            }
            "--seat" -> {
                seat = valueAfter(args, index, "--seat").toIntOrNull() ?: fail("--seat needs 0 or 1")
                index++
            }
            else -> fail("unknown argument \"$arg\"")
        }
        index++
    }
    require(seat == 0 || seat == 1) { "$USAGE\n\n--seat must be 0 or 1, was $seat" }
    return CliOptions(seed ?: System.nanoTime(), PlayerId(seat), vsRandom, mulligans)
}

/** The token after the flag at [index], or a usage failure if the flag ends the argument list. */
private fun valueAfter(
    args: Array<String>,
    index: Int,
    flag: String,
): String = args.getOrNull(index + 1) ?: fail("$flag needs a value")

/** Throws a usage-carrying [IllegalArgumentException] with [reason]. */
private fun fail(reason: String): Nothing = throw IllegalArgumentException("$USAGE\n\n$reason")
