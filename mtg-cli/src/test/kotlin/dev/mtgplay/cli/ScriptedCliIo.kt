package dev.mtgplay.cli

/**
 * A scripted [CliIo] for tests: it replays [inputs] in order and captures every printed line, so a
 * whole game can be driven deterministically with no real stdin (P6.4 DoD, the scripted-I/O smoke).
 *
 * When the scripted [inputs] run dry, [readLine] returns `null` - which the driver treats as a blank
 * line, i.e. "take the safe default" - so a short script can start a game and let the pass-heavy
 * defaults carry it to its end.
 *
 * @property output every line the driver printed, in order; the test asserts landmarks against it.
 */
class ScriptedCliIo(
    inputs: List<String> = emptyList(),
) : CliIo {
    private val pending: ArrayDeque<String> = ArrayDeque(inputs)

    val output: MutableList<String> = mutableListOf()

    override fun writeLine(line: String) {
        output.add(line)
    }

    override fun readLine(): String? = pending.removeFirstOrNull()

    /** The whole captured transcript as one string, for substring assertions. */
    fun transcript(): String = output.joinToString("\n")
}
