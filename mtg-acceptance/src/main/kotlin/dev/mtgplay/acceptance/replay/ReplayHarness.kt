package dev.mtgplay.acceptance.replay

import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.acceptance.driver.StateChecker
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.rules.DefaultGameEngine
import dev.mtgplay.rules.GameEngine
import dev.mtgplay.rules.MatchConfig
import dev.mtgplay.rules.decision.Decision

/**
 * The replay harness (ADR-006): a replay is `(MatchConfig, List<Decision>)`, and replaying it must
 * reproduce the original game exactly.
 *
 * "Exactly" is checked on two independent axes — the state [Fingerprint] (the rules-relevant
 * content) and the full [GameEvent] log (the derived narration) — so a divergence in either is
 * caught, and the two concerns stay separate (ADR-006: the event log is never load-bearing).
 * Replaying invariant-checks every transition like any [ScriptedGame], so a replay that somehow
 * diverges into a bad state also fails loudly.
 */
object ReplayHarness {
    /**
     * Runs a fresh game from [config], feeding it the recorded [decisions] in order. The engine
     * validates each decision against the request it regenerates, so a log that does not match the
     * config fails loudly (ADR-004). Returns the driven game for inspection.
     */
    fun replay(
        config: MatchConfig,
        decisions: List<Decision>,
        engine: GameEngine = DefaultGameEngine(),
        checker: StateChecker = StateChecker.DEFAULT,
    ): ScriptedGame {
        val game = ScriptedGame.start(config, engine, checker)
        decisions.forEach { game.apply(it) }
        return game
    }

    /**
     * Replays [original]'s decision log against a fresh game from [config] and asserts the replay
     * reproduces it on both axes: identical final-state [fingerprint] and identical event log.
     * Returns the outcome; [ReplayOutcome.reproduced] is true only when both match.
     */
    fun verifyReproduces(
        config: MatchConfig,
        original: ScriptedGame,
        engine: GameEngine = DefaultGameEngine(),
    ): ReplayOutcome {
        val replayed = replay(config, original.decisions, engine)
        val originalFingerprint = fingerprint(original.state)
        val replayedFingerprint = fingerprint(replayed.state)
        val originalEvents: List<GameEvent> = original.state.events
        val replayedEvents: List<GameEvent> = replayed.state.events
        return ReplayOutcome(
            fingerprintMatches = originalFingerprint == replayedFingerprint,
            eventLogMatches = originalEvents == replayedEvents,
            originalFingerprint = originalFingerprint,
            replayedFingerprint = replayedFingerprint,
        )
    }
}

/**
 * The result of a replay-reproduction check (ADR-006).
 *
 * @property fingerprintMatches whether the replayed final state fingerprints identically.
 * @property eventLogMatches whether the replayed event log is identical.
 * @property originalFingerprint the original run's final-state fingerprint.
 * @property replayedFingerprint the replay's final-state fingerprint.
 */
data class ReplayOutcome(
    val fingerprintMatches: Boolean,
    val eventLogMatches: Boolean,
    val originalFingerprint: Fingerprint,
    val replayedFingerprint: Fingerprint,
) {
    /** True only when the replay reproduced the original on both the state and the event axes. */
    val reproduced: Boolean get() = fingerprintMatches && eventLogMatches
}
