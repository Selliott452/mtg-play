package dev.mtgplay.acceptance.fuzz

import dev.mtgplay.acceptance.replay.Fingerprint
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.rules.MatchConfig
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequestId
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A self-contained, text-serialised reproduction of a fuzz failure (deliverable 3 of P3.3).
 *
 * When a seed fails — an invariant violation, an enumeration-completeness [ProbeFailure], or any
 * other engine throwable — the harness captures everything needed to replay it: the seed and the
 * pre-game parameters (so the exact [MatchConfig] can be rebuilt given the card definitions), the
 * full decision log up to the failure (the ADR-006 replay record), the [Fingerprint] of the failing
 * state, and a description of the failure. It is written to disk *and* summarised inline in the test
 * failure message, so a CI run surfaces the essentials in the log and the full record on disk.
 *
 * **Why definitions are not serialised.** A [CardDefinition] is code (a spell's resolution effect),
 * not data, so it cannot be round-tripped through text. The repro records each library as its list
 * of printed card *names*; [toConfig] rebuilds the match by pairing those names with a supplied
 * definition registry (`MvpCards.definitions` in practice). This is the one irreducible external
 * input — everything else needed to reproduce the failure is in the file.
 *
 * @property seed the match seed (ADR-006).
 * @property libraries each seat's pre-game deck as printed-card references, in deck order.
 * @property startingPlayer the seat that took the first turn, or `null` if the seed chose it.
 * @property startingHandSize the opening-hand size the match used.
 * @property decisions the decision log applied up to (and, for a violation, including) the failure.
 * @property failureType the simple class name of the failure throwable.
 * @property failureDetail the failure throwable's message (possibly multi-line).
 * @property fingerprint the fingerprint of the failing state (ADR-006).
 * @property probeOptionLabel the offending option's label if the failure was a [ProbeFailure], else
 *   `null` — recorded so a replay knows which probe to re-run.
 */
data class FuzzRepro(
    val seed: Long,
    val libraries: Map<PlayerId, List<CardRef>>,
    val startingPlayer: PlayerId?,
    val startingHandSize: Int,
    val decisions: List<Decision>,
    val failureType: String,
    val failureDetail: String,
    val fingerprint: Fingerprint,
    val probeOptionLabel: String?,
) {
    /**
     * Rebuilds the [MatchConfig] this repro was produced from, pairing the recorded library card
     * names with [definitions] (the code the names cannot carry). The result is identical to the
     * original config, so replaying [decisions] against it reproduces the failure.
     */
    fun toConfig(definitions: Map<CardRef, CardDefinition>): MatchConfig =
        MatchConfig(
            seed = seed,
            libraries = libraries,
            definitions = definitions,
            startingHandSize = startingHandSize,
            startingPlayer = startingPlayer,
        )

    /**
     * A compact multi-line digest of this repro for the [FuzzFailure] message — the essentials a CI
     * log should show without opening the file: the failure, the fingerprint, the decision count,
     * and the seed and per-seat deck shapes needed to re-run it.
     */
    fun inlineSummary(): String =
        buildString {
            val firstDetailLine = failureDetail.lineSequence().firstOrNull().orEmpty()
            appendLine("  failure: $failureType: $firstDetailLine")
            probeOptionLabel?.let { appendLine("  probe option: $it") }
            appendLine("  fingerprint: ${fingerprint.value}")
            appendLine("  decisions: ${decisions.size}")
            val starter = startingPlayer?.seat ?: "seed-chosen"
            appendLine("  config: seed=$seed startingPlayer=$starter startingHandSize=$startingHandSize")
            libraries.entries
                .sortedBy { it.key.seat }
                .forEach { (seat, cards) -> appendLine("  seat ${seat.seat} deck: ${describeDeck(cards)}") }
        }

    /** The canonical text form written to disk and parsed back by [read]. */
    fun render(): String =
        buildString {
            appendLine(FORMAT_HEADER)
            appendLine("seed $seed")
            appendLine("startingPlayer ${startingPlayer?.seat ?: NULL_TOKEN}")
            appendLine("startingHandSize $startingHandSize")
            appendLine("fingerprint ${fingerprint.value}")
            appendLine("failureType ${failureType.replace('\n', ' ')}")
            appendLine("probeOption ${probeOptionLabel?.replace('\n', ' ') ?: NULL_TOKEN}")
            libraries.entries
                .sortedBy { it.key.seat }
                .forEach { (seat, cards) ->
                    appendLine("library ${seat.seat} ${cards.joinToString(",") { it.name }}")
                }
            appendLine("decisions ${decisions.size}")
            decisions.forEach { appendLine(renderDecision(it)) }
            appendLine(FAILURE_DETAIL_MARKER)
            append(failureDetail)
        }

    /**
     * Writes this repro to [directory] as `<timestamp>-seed<seed>.txt`, creating the directory if
     * needed, and returns the path written. The timestamp-plus-seed name keeps concurrent failures
     * from colliding.
     */
    fun writeTo(directory: Path): Path {
        Files.createDirectories(directory)
        val stamp = LocalDateTime.now().format(TIMESTAMP)
        val path = directory.resolve("$stamp-seed$seed.txt")
        Files.writeString(path, render())
        return path
    }

    companion object {
        private const val FORMAT_HEADER: String = "mtg-play-fuzz-repro v1"
        private const val NULL_TOKEN: String = "-"
        private const val FAILURE_DETAIL_MARKER: String = "failureDetail"
        private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

        /** Reads a repro from [path] (the inverse of [render]/[writeTo]). */
        fun read(path: Path): FuzzRepro = parse(Files.readString(path))

        /**
         * Parses a repro from its [text] form. The record splits cleanly into three regions — the
         * `key value` header lines, the `S`/`M` decision lines, and the free-form failure detail
         * after the [FAILURE_DETAIL_MARKER] — so each is extracted with a single pass rather than a
         * branchy state machine. Fails loudly on a malformed record.
         */
        fun parse(text: String): FuzzRepro {
            val lines = text.lines()
            require(lines.firstOrNull() == FORMAT_HEADER) { "not a fuzz repro: missing header \"$FORMAT_HEADER\"" }
            val detailIndex = lines.indexOf(FAILURE_DETAIL_MARKER)
            require(detailIndex >= 0) { "not a fuzz repro: missing \"$FAILURE_DETAIL_MARKER\" marker" }
            val body = lines.subList(1, detailIndex)
            val detail = lines.subList(detailIndex + 1, lines.size).joinToString("\n")

            val headers =
                body
                    .filterNot { it.isDecisionLine() || it.startsWith("library ") || it.isBlank() }
                    .associate { line -> line.split(" ", limit = 2).let { it[0] to it.getOrElse(1) { "" } } }
            val libraries = body.filter { it.startsWith("library ") }.map(::parseLibrary).toMap()
            val decisions = body.filter { it.isDecisionLine() }.map(::parseDecision)

            return FuzzRepro(
                seed = headers.getValue("seed").toLong(),
                libraries = libraries,
                startingPlayer = parseSeat(headers.getValue("startingPlayer")),
                startingHandSize = headers["startingHandSize"]?.toInt() ?: MatchConfig.DEFAULT_STARTING_HAND_SIZE,
                decisions = decisions,
                failureType = headers["failureType"].orEmpty(),
                failureDetail = detail,
                fingerprint = Fingerprint(headers.getValue("fingerprint")),
                probeOptionLabel = headers["probeOption"]?.takeUnless { it == NULL_TOKEN },
            )
        }

        // A decision line is "<kind> <seat> <ordinal> <payload>"; the payload index is named so it
        // is not read as a magic number (seat/ordinal at 1/2 are in detekt's ignore set).
        private const val DECISION_PAYLOAD_FIELD: Int = 3

        /** A `Nx Card` multiset summary of a deck, sorted by card name, for the inline summary. */
        private fun describeDeck(cards: List<CardRef>): String =
            cards
                .groupingBy { it.name }
                .eachCount()
                .entries
                .sortedBy { it.key }
                .joinToString(", ") { "${it.value}x ${it.key}" }

        private fun String.isDecisionLine(): Boolean = startsWith("S ") || startsWith("M ")

        private fun parseSeat(token: String): PlayerId? =
            token.takeUnless { it == NULL_TOKEN }?.let { PlayerId(it.toInt()) }

        private fun parseLibrary(line: String): Pair<PlayerId, List<CardRef>> {
            // "library <seat> <name,name,...>" — names contain spaces but never commas.
            val parts = line.split(" ", limit = 3)
            val seat = PlayerId(parts[1].toInt())
            val names = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
            val cards = names?.split(",")?.map { CardRef(it) } ?: emptyList()
            return seat to cards
        }

        private fun renderDecision(decision: Decision): String {
            val id = decision.requestId
            return when (decision) {
                is Decision.SingleSelect -> "S ${id.seat.seat} ${id.ordinal} ${decision.index}"
                is Decision.MultiSelect -> {
                    val indices = decision.indices.joinToString(",").ifEmpty { NULL_TOKEN }
                    "M ${id.seat.seat} ${id.ordinal} $indices"
                }
            }
        }

        private fun parseDecision(line: String): Decision {
            val parts = line.split(" ")
            val id = DecisionRequestId(PlayerId(parts[1].toInt()), parts[2].toInt())
            val payload = parts[DECISION_PAYLOAD_FIELD]
            return when (parts[0]) {
                "S" -> Decision.SingleSelect(id, payload.toInt())
                "M" -> {
                    val indices = if (payload == NULL_TOKEN) emptyList() else payload.split(",").map { it.toInt() }
                    Decision.MultiSelect(id, indices)
                }
                else -> error("unrecognised decision line: \"$line\"")
            }
        }
    }
}
