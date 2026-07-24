package dev.mtgplay.cli

import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.DefaultGameEngine
import dev.mtgplay.rules.GameEngine
import dev.mtgplay.rules.MatchResult
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest

/**
 * The driver loop (P6.4 deliverable 3): start the match, answer each pending [DecisionRequest] until
 * [AdvanceResult.GameOver], and print the closing report.
 *
 * The CLI is a thin driver over the public engine surface - it holds no game logic (ADR-004): it
 * renders the paused state, gathers or auto-answers the decision, and calls `advance`. Every decision
 * - human, auto-passed, single-plan-collapsed, or the random opponent's - flows through the same
 * `advance`, so the game the loop plays is a faithful `(seed, decisions)` replay record (ADR-006).
 *
 * @property io the terminal seam; scripted in tests.
 * @property setup the match and seat names.
 * @property options the run's parsed options (seats, mode).
 * @property autoPass the pass-until / single-plan-collapse policy.
 * @property engine the rules engine (the standard [DefaultGameEngine] by default).
 * @property maxDecisions a runaway guard: the loop fails loudly rather than hanging if the game does
 *   not terminate within this many decisions.
 */
class CliDriver(
    private val io: CliIo,
    private val setup: MatchSetup,
    private val options: CliOptions,
    private val autoPass: AutoPassPolicy = AutoPassPolicy(),
    private val engine: GameEngine = DefaultGameEngine(),
    private val maxDecisions: Int = DEFAULT_MAX_DECISIONS,
) {
    private val randomChooser: RandomLegalChooser? =
        if (options.vsRandom) RandomLegalChooser(seed = setup.config.seed) else null

    /** Plays the whole game and returns its result; prints the banner, decisions, and closing report. */
    fun run(): MatchResult {
        printBanner(io, setup, options)
        var result = engine.start(setup.config)
        var decisions = 0
        while (result is AdvanceResult.NeedsDecision) {
            check(decisions < maxDecisions) { "game did not terminate within $maxDecisions decisions" }
            val decision = decideFor(result.state, result.request)
            result = engine.advance(result.state, decision)
            decisions++
        }
        val over = result as AdvanceResult.GameOver
        printResult(io, setup, over.result, decisions)
        return over.result
    }

    /** The answer for one request: the random opponent's seat is chosen for; a human seat is prompted. */
    private fun decideFor(
        state: GameState,
        request: DecisionRequest,
    ): Decision {
        if (request.seat !in options.humanSeats) return randomChoice(request)
        return humanDecision(state, request)
    }

    /** The seeded random-legal opponent's answer (never renders the human's hidden information). */
    private fun randomChoice(request: DecisionRequest): Decision {
        val chooser = randomChooser ?: error("a non-human seat surfaced with no random chooser configured")
        return chooser.choose(request)
    }

    /** A human seat's answer: an auto-answer if the policy handles it, otherwise a rendered prompt. */
    private fun humanDecision(
        state: GameState,
        request: DecisionRequest,
    ): Decision {
        val auto = autoAnswer(request, state, autoPass)
        if (auto != null) {
            announceAuto(request)
            return auto
        }
        return promptHuman(state, request)
    }

    /** Notes a single-plan auto-payment in the log; auto-passes stay silent (that is the point). */
    private fun announceAuto(request: DecisionRequest) {
        if (request is DecisionRequest.ChoosePaymentPlan) {
            io.writeLine("[auto] Only one way to pay for ${request.card.name}; paying it.")
        }
    }

    /** Renders the view and menu, then reads and re-prompts until the input parses to a legal decision. */
    private fun promptHuman(
        state: GameState,
        request: DecisionRequest,
    ): Decision {
        val view = MatchView(state, request.seat, setup.names)
        showDecision(view, request)
        while (true) {
            // End of input (a real terminal's Ctrl+D, or a scripted source running dry) reads as a
            // blank line - "take the safe default" - so the loop never crashes and always progresses.
            val line = io.readLine().orEmpty()
            when (val command = line.trim()) {
                "?" -> HELP_LINES.forEach(io::writeLine)
                "v" -> showDecision(view, request)
                "" -> return defaultDecision(request)
                else -> {
                    val decision = parseDecision(request, command)
                    if (decision != null) return decision
                    io.writeLine("Invalid input - enter a listed number, or ? for help.")
                }
            }
        }
    }

    /** Prints the full board view and the request's menu. */
    private fun showDecision(
        view: MatchView,
        request: DecisionRequest,
    ) {
        renderView(view).forEach(io::writeLine)
        io.writeLine("")
        renderMenu(view, request).forEach(io::writeLine)
    }

    companion object {
        /** Runaway guard: far above any real game's decision count, but bounds a stuck engine. */
        const val DEFAULT_MAX_DECISIONS: Int = 200_000
    }
}
