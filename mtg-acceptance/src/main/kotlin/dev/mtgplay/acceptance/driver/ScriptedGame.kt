package dev.mtgplay.acceptance.driver

import dev.mtgplay.acceptance.invariant.CardCensus
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.DefaultGameEngine
import dev.mtgplay.rules.GameEngine
import dev.mtgplay.rules.MatchConfig
import dev.mtgplay.rules.MatchResult
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.pendingRequestOf

/**
 * The correctness rig's scripted-game driver — the durable evolution of the P1.2 test support.
 *
 * A game is started from a [MatchConfig] and driven forward by answering the pending
 * [DecisionRequest]: [pass] a priority window, [discard] by index, or [respond] with any
 * [Responder]. The convenience walkers [passUntil] and [playToCompletion] chain those.
 *
 * **Every single transition is invariant-checked.** After the initial advance and after each
 * decision, the driver runs its [StateChecker] against the produced state (with the game's
 * baseline card census) and throws [InvariantViolationException] on any violation — engine
 * wrongness fails the run loudly and immediately, never surviving as plausible-looking state
 * (PLAN.md §2.3, §7). The [StateChecker] is injectable so a test can prove the invocation happens.
 *
 * The driver accumulates the ordered [decisions] as it plays — the replay record (ADR-006) — and a
 * [RecordedPause] per suspension for assertions.
 *
 * Instances are single-threaded, stateful walkers: the fluent methods advance internal state and
 * return `this`. Start one with [start].
 */
class ScriptedGame private constructor(
    private val engine: GameEngine,
    private val checker: StateChecker,
    private val baseline: CardCensus,
    firstResult: AdvanceResult,
) {
    private var currentResult: AdvanceResult = firstResult
    private val decisionLog: MutableList<Decision> = mutableListOf()
    private val pauseLog: MutableList<RecordedPause> = mutableListOf()

    init {
        recordPause(firstResult)
    }

    /** The latest engine result: either a suspension or game over. */
    val current: AdvanceResult get() = currentResult

    /** The current game state, whether paused or final. */
    val state: GameState get() = stateOf(currentResult)

    /** The pending request if the game is paused, or `null` if it is over. */
    val pendingRequest: DecisionRequest?
        get() = (currentResult as? AdvanceResult.NeedsDecision)?.request

    /** Whether the game has ended. */
    val isOver: Boolean get() = currentResult is AdvanceResult.GameOver

    /** The match result if the game is over, or `null` while it is still in progress. */
    val result: MatchResult? get() = (currentResult as? AdvanceResult.GameOver)?.result

    /** The decisions played so far, in order — the replay record (ADR-006). */
    val decisions: List<Decision> get() = decisionLog.toList()

    /** The suspensions observed so far, in order. */
    val pauses: List<RecordedPause> get() = pauseLog.toList()

    /** The baseline card census captured from the game's first state (CR 400.2). */
    val cardBaseline: CardCensus get() = baseline

    /**
     * Passes the pending priority window (CR 117.3d). Fails loudly if the game is not paused on a
     * [DecisionRequest.ChooseAction].
     */
    fun pass(): ScriptedGame {
        val request =
            requirePaused().request as? DecisionRequest.ChooseAction
                ?: error("the pending request is not a priority window: $pendingRequest")
        val passIndex = request.options.indexOfFirst { it is PriorityOption.Pass }
        check(passIndex >= 0) { "CR 117.3d: passing must always be enumerated, options were ${request.options}" }
        return apply(Decision.SingleSelect(request.id, passIndex))
    }

    /**
     * Answers the pending cleanup discard (CR 514.1) by selecting the cards at [indices], in the
     * order they should be put into the graveyard. Fails loudly if the game is not paused on a
     * [DecisionRequest.ChooseDiscards].
     */
    fun discard(vararg indices: Int): ScriptedGame {
        val request =
            requirePaused().request as? DecisionRequest.ChooseDiscards
                ?: error("the pending request is not a discard request: $pendingRequest")
        return apply(Decision.MultiSelect(request.id, indices.toList()))
    }

    /** Answers the pending request with [responder]. Fails loudly if the game is over. */
    fun respond(responder: Responder): ScriptedGame {
        val paused = requirePaused()
        return apply(responder.respond(paused.request, paused.state))
    }

    /**
     * Applies an explicit [decision] to the pending request — the low-level step the replay
     * harness uses to feed a recorded log. The engine validates it against the pending request and
     * rejects a mismatch loudly (ADR-004); the produced state is then invariant-checked.
     */
    fun apply(decision: Decision): ScriptedGame {
        val paused = requirePaused()
        decisionLog += decision
        record(engine.advance(paused.state, decision))
        return this
    }

    /**
     * Advances with [Responders.PASS_AND_DISCARD_LOWEST] until [predicate] holds of the current
     * state. Fails loudly if the game ends before the predicate is ever satisfied, or if it does
     * not settle within [maxSteps] steps (a runaway guard).
     */
    fun passUntil(
        maxSteps: Int = DEFAULT_MAX_STEPS,
        predicate: (GameState) -> Boolean,
    ): ScriptedGame {
        var steps = 0
        while (!predicate(state)) {
            check(!isOver) { "the game ended before the passUntil predicate was satisfied" }
            check(steps < maxSteps) { "passUntil did not satisfy its predicate within $maxSteps steps" }
            respond(Responders.PASS_AND_DISCARD_LOWEST)
            steps++
        }
        return this
    }

    /**
     * Drives the game to completion with [responder]. Fails loudly if the game does not end within
     * [turnCap] turns or [maxDecisions] decisions — the termination bound that keeps a stuck or
     * looping engine from hanging the run.
     */
    fun playToCompletion(
        responder: Responder,
        turnCap: Int = DEFAULT_TURN_CAP,
        maxDecisions: Int = DEFAULT_MAX_DECISIONS,
    ): ScriptedGame {
        while (!isOver) {
            check(state.turn.number <= turnCap) {
                "game did not terminate within $turnCap turns (reached turn ${state.turn.number})"
            }
            check(decisionLog.size < maxDecisions) {
                "game did not terminate within $maxDecisions decisions"
            }
            respond(responder)
        }
        return this
    }

    private fun record(result: AdvanceResult) {
        val produced = stateOf(result)
        val violations = checker.check(produced, baseline)
        if (violations.isNotEmpty()) {
            throw InvariantViolationException(violations, decisionLog.toList(), produced)
        }
        currentResult = result
        recordPause(result)
    }

    private fun recordPause(result: AdvanceResult) {
        if (result is AdvanceResult.NeedsDecision) pauseLog += RecordedPause(result.state, result.request)
    }

    private fun requirePaused(): AdvanceResult.NeedsDecision =
        currentResult as? AdvanceResult.NeedsDecision
            ?: error("the game is over; no decision is pending")

    private fun stateOf(result: AdvanceResult): GameState =
        when (result) {
            is AdvanceResult.NeedsDecision -> result.state
            is AdvanceResult.GameOver -> result.state
        }

    companion object {
        /** Generous default turn cap for [playToCompletion]; lands-only decks out near turn 108. */
        const val DEFAULT_TURN_CAP: Int = 200

        /** Runaway guard for [playToCompletion]: far above any lands-only game's decision count. */
        const val DEFAULT_MAX_DECISIONS: Int = 100_000

        /** Runaway guard for [passUntil]. */
        const val DEFAULT_MAX_STEPS: Int = 100_000

        /**
         * Starts a new game from [config], advancing to the first decision (or game over), and
         * invariant-checks the initial state. The baseline card census is captured here, from the
         * game's first state, and every later state is measured against it (CR 400.2).
         *
         * @param config the match to start (ADR-006).
         * @param engine the engine under test; the standard [DefaultGameEngine] by default.
         * @param checker the per-transition check; the full [StateChecker.DEFAULT] by default.
         */
        fun start(
            config: MatchConfig,
            engine: GameEngine = DefaultGameEngine(),
            checker: StateChecker = StateChecker.DEFAULT,
        ): ScriptedGame {
            val firstResult = engine.start(config)
            val firstState =
                when (firstResult) {
                    is AdvanceResult.NeedsDecision -> firstResult.state
                    is AdvanceResult.GameOver -> firstResult.state
                }
            val baseline = CardCensus.of(firstState)
            val violations = checker.check(firstState, baseline)
            check(violations.isEmpty()) { "initial state violates invariants: $violations" }
            return ScriptedGame(engine, checker, baseline, firstResult)
        }

        /**
         * Resumes driving from a paused [initialState] — a state a test constructed (or doctored
         * from an engine start, e.g. planting battlefield fixtures while the play-land action is
         * still P2.2's). The pending request is re-derived from the state alone
         * ([pendingRequestOf], ADR-004), the state is invariant-checked, and the baseline card
         * census is captured *here* — conservation is measured against this state, exactly as a
         * replay of the same scenario would measure it.
         */
        fun startFrom(
            initialState: GameState,
            engine: GameEngine = DefaultGameEngine(),
            checker: StateChecker = StateChecker.DEFAULT,
        ): ScriptedGame {
            val request =
                pendingRequestOf(initialState)
                    ?: error("startFrom requires a state paused at a decision point (ADR-004)")
            val baseline = CardCensus.of(initialState)
            val violations = checker.check(initialState, baseline)
            check(violations.isEmpty()) { "initial state violates invariants: $violations" }
            return ScriptedGame(engine, checker, baseline, AdvanceResult.NeedsDecision(initialState, request))
        }
    }
}
