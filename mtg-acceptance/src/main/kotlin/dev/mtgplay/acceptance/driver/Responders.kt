package dev.mtgplay.acceptance.driver

import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption

/**
 * The stock [Responder] policies the scripted driver uses by default.
 *
 * Auto-responders live in the harness, not the engine: the engine never auto-passes (ADR-004), so
 * convenience like "pass every window" is a driver's job.
 */
object Responders {
    /**
     * Passes every priority window and, when forced to discard down to maximum hand size
     * (CR 514.1), discards the lowest-indexed cards. The deterministic baseline policy for driving
     * lands-only games to deck-out and for [ScriptedGame.passUntil].
     */
    val PASS_AND_DISCARD_LOWEST: Responder =
        Responder { request, _ ->
            when (request) {
                is DecisionRequest.ChooseAction -> {
                    val passIndex = request.options.indexOfFirst { it is PriorityOption.Pass }
                    check(passIndex >= 0) {
                        "CR 117.3d: passing must always be enumerated, options were ${request.options}"
                    }
                    Decision.SingleSelect(request.id, passIndex)
                }
                is DecisionRequest.ChooseDiscards ->
                    Decision.MultiSelect(request.id, (0 until request.count).toList())
            }
        }
}
