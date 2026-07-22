package dev.mtgplay.acceptance.fuzz

import dev.mtgplay.acceptance.driver.InvariantViolationException
import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.acceptance.replay.Fingerprint
import dev.mtgplay.acceptance.replay.fingerprint
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.event.LossReason
import dev.mtgplay.rules.DefaultGameEngine
import dev.mtgplay.rules.GameEngine
import dev.mtgplay.rules.MatchConfig
import java.nio.file.Path

/**
 * The project's permanent fuzzing rig (P3.3): one driver that plays a whole [FuzzCorpus] of random
 * legal playouts, unifying the loops the P2.x/P3.x smoke specs previously hand-rolled per spec.
 *
 * For each seed the harness starts a [ScriptedGame] — which invariant-checks **every** transition
 * (PLAN.md §2.3) — drives it to a conclusion or a cap with the corpus' responder, and at sampled
 * decision windows runs the [EnumerationProbe] so ADR-005's "no phantom options" is continuously
 * fuzzed. It classifies each game ([SeedOutcome]) and returns a [CorpusReport].
 *
 * **Failure handling (deliverable 3).** Any invariant violation, enumeration-completeness
 * [ProbeFailure], or engine loud-failure aborts the whole run: the harness persists a self-contained
 * [FuzzRepro] to the corpus' failure directory and throws a [FuzzFailure] naming the file and
 * summarising it inline. So a returned report is itself proof that every seed played clean.
 *
 * Both the engine and the harness are stateless, so one instance drives any number of corpora.
 */
object FuzzHarness {
    /**
     * Plays every seed of [corpus] and returns its [CorpusReport]. The optional [inspect] callback
     * runs once per seed against the finished [ScriptedGame] and its classified [SeedOutcome] — the
     * seam where a suite extracts card-specific facts (which deck's kill, combat versus burn) from
     * the full event log while the harness itself stays card-agnostic.
     *
     * @param engine the engine under test; the standard [DefaultGameEngine] by default. Injectable
     *   so a test can drive the harness with a deliberately-misbehaving engine (deliverable 3's
     *   repro round-trip) without weakening the real one.
     * @param failureDir where a persisted repro is written when a seed fails (deliverable 3);
     *   defaults to `build/fuzz-failures`, which Gradle resolves under the acceptance module.
     */
    fun run(
        corpus: FuzzCorpus,
        engine: GameEngine = DefaultGameEngine(),
        failureDir: Path = Path.of("build", "fuzz-failures"),
        inspect: (game: ScriptedGame, outcome: SeedOutcome) -> Unit = { _, _ -> },
    ): CorpusReport {
        val outcomes = corpus.seeds.map { seed -> runSeed(corpus, engine, failureDir, seed, inspect) }
        return CorpusReport(corpus.name, outcomes)
    }

    private fun runSeed(
        corpus: FuzzCorpus,
        engine: GameEngine,
        failureDir: Path,
        seed: Long,
        inspect: (ScriptedGame, SeedOutcome) -> Unit,
    ): SeedOutcome {
        val config = corpus.configForSeed(seed)
        var game: ScriptedGame? = null
        var probedWindows = 0
        var probedOptions = 0
        // These are the engine's loud-failure idioms (CONVENTIONS.md): an invariant violation or
        // enumeration-completeness ProbeFailure (both IllegalStateException), a rejected decision
        // (IllegalArgumentException), and an unimplemented corner (`TODO()` -> NotImplementedError).
        // The failure is captured, not rethrown inline, so a repro is persisted from one place.
        val failure: Throwable? =
            try {
                val responder = corpus.responderForSeed(seed)
                val started = ScriptedGame.start(config, engine)
                game = started
                while (!started.isOver &&
                    started.state.turn.number <= corpus.turnCap &&
                    started.decisions.size < corpus.decisionCap
                ) {
                    val request = started.pendingRequest ?: break
                    if (corpus.probePolicy.shouldProbe(seed, started.decisions.size)) {
                        probedWindows += 1
                        probedOptions += EnumerationProbe.probe(engine, started.state, request)
                    }
                    started.respond(responder)
                }
                null
            } catch (thrown: IllegalStateException) {
                thrown
            } catch (thrown: IllegalArgumentException) {
                thrown
            } catch (thrown: NotImplementedError) {
                thrown
            }
        if (failure != null) {
            val repro = reproOf(seed, config, game, failure)
            val path = repro.writeTo(failureDir)
            throw FuzzFailure(corpus.name, seed, path, repro.inlineSummary(), failure)
        }
        val finished = checkNotNull(game) { "a started game is non-null once past ScriptedGame.start" }
        val outcome = classify(seed, finished, probedWindows, probedOptions)
        inspect(finished, outcome)
        return outcome
    }

    private fun classify(
        seed: Long,
        game: ScriptedGame,
        probedWindows: Int,
        probedOptions: Int,
    ): SeedOutcome {
        val result = game.result
        val outcome =
            if (!game.isOver || result == null) {
                Outcome.INCONCLUSIVE
            } else {
                when (result.reason) {
                    LossReason.ATTEMPTED_DRAW_FROM_EMPTY_LIBRARY -> Outcome.DECISIVE_DECK_OUT
                    LossReason.LIFE_TOTAL_ZERO_OR_LESS -> Outcome.DECISIVE_LIFE_LOSS
                }
            }
        val events = game.state.events
        return SeedOutcome(
            seed = seed,
            outcome = outcome,
            result = if (outcome == Outcome.INCONCLUSIVE) null else result,
            finalTurnNumber = game.state.turn.number,
            decisionCount = game.decisions.size,
            creatureDeaths = events.count { it is GameEvent.CreatureDied },
            fizzles = events.count { it is GameEvent.SpellFizzled },
            probedWindows = probedWindows,
            probedOptions = probedOptions,
        )
    }

    private fun reproOf(
        seed: Long,
        config: MatchConfig,
        game: ScriptedGame?,
        failure: Throwable,
    ): FuzzRepro {
        // An invariant violation carries its own replay record and the offending state (the decision
        // that produced it is already in the log); every other failure uses the driver's log and its
        // current (paused) state — replaying that log re-reaches the failure point.
        val decisions = (failure as? InvariantViolationException)?.decisions ?: game?.decisions.orEmpty()
        val failingState = (failure as? InvariantViolationException)?.state ?: game?.state
        return FuzzRepro(
            seed = seed,
            libraries = config.libraries,
            startingPlayer = config.startingPlayer,
            startingHandSize = config.startingHandSize,
            decisions = decisions,
            failureType = failure::class.simpleName ?: "Throwable",
            failureDetail = failure.message ?: "(no message)",
            fingerprint = failingState?.let(::fingerprint) ?: Fingerprint("(no state captured)"),
            probeOptionLabel = (failure as? ProbeFailure)?.optionLabel,
        )
    }
}
