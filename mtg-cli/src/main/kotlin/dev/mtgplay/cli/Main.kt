package dev.mtgplay.cli

/**
 * The `mtg-cli` entry point (P6.4): parse the arguments, build the MVP matchup, and drive a full
 * game at the terminal via [SystemCliIo].
 *
 * Run it with `./gradlew :mtg-cli:run` (add `--args="--vs-random --seed 42"` to pass flags). A bad
 * flag or `--help` prints the usage and exits without starting a game.
 */
fun main(args: Array<String>) {
    val options =
        try {
            parseArgs(args)
        } catch (usage: IllegalArgumentException) {
            println(usage.message)
            return
        }
    val setup = buildMvpMatch(seed = options.seed, mulligans = options.mulligans)
    CliDriver(io = SystemCliIo(), setup = setup, options = options).run()
}
