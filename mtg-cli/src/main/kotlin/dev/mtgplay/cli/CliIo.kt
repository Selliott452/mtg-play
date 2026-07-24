package dev.mtgplay.cli

/**
 * The CLI's terminal seam (P6.4): every line the driver prints or reads goes through this
 * interface rather than `System.out`/`System.in` directly, so a test can script a whole game
 * deterministically without touching real stdin (the scripted-I/O smoke, DoD).
 *
 * The driver is the only writer and reader; keeping the surface this small (one write, one read)
 * is what lets the scripted double be a few lines. No game logic lives here - this is pure
 * transport.
 */
interface CliIo {
    /** Prints [line] followed by a newline. */
    fun writeLine(line: String)

    /**
     * Reads the next input line, or `null` at end of input (a real terminal's Ctrl+D, or a
     * scripted source running dry). The driver treats `null` the same as a blank line - the
     * "take the safe default" input - so end of input never crashes the loop.
     */
    fun readLine(): String?
}

/**
 * The production [CliIo]: prints to `System.out` and reads from `System.in` via [readlnOrNull].
 * Used by [main]; tests use a scripted double instead.
 */
class SystemCliIo : CliIo {
    override fun writeLine(line: String) {
        println(line)
    }

    override fun readLine(): String? = readlnOrNull()
}
