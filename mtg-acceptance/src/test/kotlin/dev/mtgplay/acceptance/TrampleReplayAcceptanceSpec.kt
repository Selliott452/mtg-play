package dev.mtgplay.acceptance

import dev.mtgplay.acceptance.driver.RandomLegalResponder
import dev.mtgplay.acceptance.driver.ScriptedGame
import dev.mtgplay.acceptance.replay.ReplayHarness
import dev.mtgplay.rules.MatchConfig
import dev.mtgplay.rules.decision.DecisionRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue

/**
 * Replay through a trample-decision game (ADR-006, deliverable 5): a real-card keyword game that
 * surfaces at least one [DecisionRequest.AssignTrampleDamage] pause — a Rancor'd attacker blocked
 * with above-lethal excess — replays from its recorded decision log to an identical final state and
 * event log, so the new decision kind is faithful under the replay contract.
 */
class TrampleReplayAcceptanceSpec :
    StringSpec({

        "ADR-006: a keyword game containing a trample-assignment decision replays to an identical state" {
            val (config, game) = firstGameWithTrampleDecision()
            // The game did surface the new decision kind — the replay is exercising it, not a plain game.
            game.pauses.any { it.request is DecisionRequest.AssignTrampleDamage }.shouldBeTrue()

            val outcome = ReplayHarness.verifyReproduces(config, game)
            outcome.fingerprintMatches.shouldBeTrue()
            outcome.eventLogMatches.shouldBeTrue()
        }
    })

// The first seed in 0..999 whose random-legal keyword game surfaces a trample-assignment decision,
// with the finished game (deterministic: engine + responder are pure functions of the seed, ADR-006).
private fun firstGameWithTrampleDecision(): Pair<MatchConfig, ScriptedGame> {
    for (seed in 0L..999L) {
        val config = boglesKeywordConfig(seed)
        val game =
            ScriptedGame
                .start(config)
                .playToCompletion(RandomLegalResponder(seed), turnCap = 80, maxDecisions = 60_000)
        if (game.pauses.any { it.request is DecisionRequest.AssignTrampleDamage }) return config to game
    }
    error("no seed in 0..999 produced a trample-assignment decision")
}
