package dev.mtgplay.acceptance.fuzz

import dev.mtgplay.acceptance.driver.InvariantViolationException
import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.acceptance.replay.Fingerprint
import dev.mtgplay.acceptance.replay.fingerprint
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.rules.DefaultGameEngine
import dev.mtgplay.rules.GameEngine
import java.nio.file.Path

/**
 * Replays a persisted [FuzzRepro] and reports whether it reproduced the original failure
 * (P3.3, deliverable 3): the proof that a repro file is a faithful, self-contained record.
 *
 * A repro carries the seed, the per-seat decks (as card names), and the decision log; [replay]
 * rebuilds the exact [dev.mtgplay.rules.MatchConfig] by pairing the names with a supplied definition
 * registry (the one external input a text file cannot carry — see [FuzzRepro]) and re-drives a
 * [ScriptedGame] through the recorded decisions. Because the engine is deterministic (ADR-006), the
 * replay reaches the same failing state, and [ReproReplayOutcome.reproduced] confirms the state
 * fingerprint and failure type match those recorded at failure.
 *
 * The three failure shapes the harness persists each replay identically: a decision that throws
 * (an invariant violation, whose own state is fingerprinted, or an engine rejection, whose pre-move
 * paused state the driver still holds) reproduces during the decision replay; an
 * enumeration-completeness probe failure — where every recorded decision applied cleanly — is
 * reproduced by re-running the probe at the paused state the decisions reach.
 */
object ReplayFromRepro {
    /** Reads the repro at [path] and [replay]s it. */
    fun replay(
        path: Path,
        definitions: Map<CardRef, CardDefinition>,
        engine: GameEngine = DefaultGameEngine(),
    ): ReproReplayOutcome = replay(FuzzRepro.read(path), definitions, engine)

    /**
     * Replays [repro] against [engine], feeding the recorded decisions to a fresh [ScriptedGame]
     * built from the repro's config, then re-probing if the recorded failure was a probe failure.
     * Returns whether the same failure reproduced (same type and same failing-state fingerprint). A
     * replay that runs clean to the end reports [ReproReplayOutcome.reproduced] false with a `null`
     * reproduced fingerprint — the repro did not reproduce, reported honestly rather than as a false
     * positive.
     */
    fun replay(
        repro: FuzzRepro,
        definitions: Map<CardRef, CardDefinition>,
        engine: GameEngine = DefaultGameEngine(),
    ): ReproReplayOutcome {
        val game = ScriptedGame.start(repro.toConfig(definitions), engine)
        val reproduced = reproduce(game, repro, engine)
        return ReproReplayOutcome(
            expectedFingerprint = repro.fingerprint,
            reproducedFingerprint = reproduced?.fingerprint,
            expectedFailureType = repro.failureType,
            reproducedFailureType = reproduced?.failureType,
        )
    }

    private fun reproduce(
        game: ScriptedGame,
        repro: FuzzRepro,
        engine: GameEngine,
    ): ReproducedFailure? = reproduceFromDecisions(game, repro) ?: reproduceFromProbe(game, repro, engine)

    // Replay the decisions in order; the first that throws is the reproduced failure. An invariant
    // violation carries its own offending state; every other throw leaves the driver on the pre-move
    // paused state, which is exactly what the harness fingerprinted.
    private fun reproduceFromDecisions(
        game: ScriptedGame,
        repro: FuzzRepro,
    ): ReproducedFailure? {
        for (decision in repro.decisions) {
            val failure = captureFailure { game.apply(decision) } ?: continue
            val state = (failure as? InvariantViolationException)?.state ?: game.state
            return ReproducedFailure(failure.simpleTypeName(), fingerprint(state))
        }
        return null
    }

    // A probe-failure repro replayed every decision clean; it is reproduced by re-running the probe
    // at the paused state those decisions reach — the same window that failed originally.
    private fun reproduceFromProbe(
        game: ScriptedGame,
        repro: FuzzRepro,
        engine: GameEngine,
    ): ReproducedFailure? {
        val request = game.pendingRequest?.takeIf { repro.probeOptionLabel != null } ?: return null
        val failure = captureFailure { EnumerationProbe.probe(engine, game.state, request) }
        return failure?.let { ReproducedFailure(it.simpleTypeName(), fingerprint(game.state)) }
    }

    // Runs [block], returning the loud-failure throwable it raised (the engine's idioms:
    // IllegalStateException — including invariant violations and probe failures —
    // IllegalArgumentException, and NotImplementedError) or `null` if it completed cleanly.
    private fun captureFailure(block: () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (failure: IllegalStateException) {
            failure
        } catch (failure: IllegalArgumentException) {
            failure
        } catch (failure: NotImplementedError) {
            failure
        }

    private fun Throwable.simpleTypeName(): String = this::class.simpleName.orEmpty()

    private data class ReproducedFailure(
        val failureType: String,
        val fingerprint: Fingerprint,
    )
}

/**
 * The result of replaying a [FuzzRepro] (P3.3, deliverable 3).
 *
 * @property expectedFingerprint the fingerprint the repro recorded at the original failure.
 * @property reproducedFingerprint the fingerprint of the replay's failing state, or `null` if the
 *   replay ran clean (no failure reproduced).
 * @property expectedFailureType the failure throwable's simple class name in the original run.
 * @property reproducedFailureType the failure throwable's simple class name on replay, or `null` if
 *   none was thrown.
 */
data class ReproReplayOutcome(
    val expectedFingerprint: Fingerprint,
    val reproducedFingerprint: Fingerprint?,
    val expectedFailureType: String,
    val reproducedFailureType: String?,
) {
    /** True when the replay failed the same way: same failure type and same failing-state fingerprint. */
    val reproduced: Boolean
        get() =
            reproducedFingerprint == expectedFingerprint &&
                reproducedFailureType == expectedFailureType
}
