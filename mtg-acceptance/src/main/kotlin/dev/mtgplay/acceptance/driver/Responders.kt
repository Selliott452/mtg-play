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
     * Passes every priority window; when forced to discard down to maximum hand size (CR 514.1),
     * discards the lowest-indexed cards; and in combat declares **no** attackers and **no**
     * blockers (a passive, do-nothing combat policy — the flagged combat behaviour of this
     * driver). The deterministic baseline policy for driving lands-only games to deck-out and for
     * [ScriptedGame.passUntil]. It never casts and never attacks or blocks, so the casting
     * requests (CR 601.2) and the blocker-ordering request (CR 509.2, reachable only after a
     * block is declared) are all unreachable for it — reaching one fails loudly instead of
     * guessing. The one exception is a target choice reached from **trigger placement** (CR 603.3d),
     * which a passive game genuinely can reach: that takes the first enumerated target.
     */
    val PASS_AND_DISCARD_LOWEST: Responder =
        Responder { request, state ->
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
                // CR 508.1 / CR 509.1: the empty selection declares no attackers / no blockers.
                is DecisionRequest.DeclareAttackers -> Decision.MultiSelect(request.id, emptyList())
                is DecisionRequest.DeclareBlockers -> Decision.MultiSelect(request.id, emptyList())
                // CR 603.3b: a passive game still fires triggers (an aura falling off returns Rancor);
                // order them in enumeration order, the deterministic identity permutation.
                is DecisionRequest.OrderTriggers ->
                    Decision.MultiSelect(request.id, request.options.indices.toList())
                // CR 702.35b: a passive game may discard a madness card at cleanup; decline the reflexive cast.
                is DecisionRequest.ChooseYesNo ->
                    Decision.SingleSelect(request.id, DecisionRequest.ChooseYesNo.DECLINE)
                // CR 603.3d: a passive game still fires triggers, and a targeted one must choose its
                // target as it is put on the stack — take the first enumerated target deterministically.
                // A targets request with no trigger placement open can only have come from a cast or an
                // activation, neither of which this policy takes, so that stays a loud failure.
                is DecisionRequest.ChooseTargets ->
                    if (state.pendingTriggerTargets != null) {
                        Decision.SingleSelect(request.id, 0)
                    } else {
                        error("the pass-everything responder never casts, but a targets request surfaced: $request")
                    }
                is DecisionRequest.ChoosePaymentPlan ->
                    error("the pass-everything responder never casts, but a payment request surfaced: $request")
                is DecisionRequest.OrderBlockers ->
                    error("the pass-everything responder never blocks, but a blocker-order request surfaced: $request")
                is DecisionRequest.AssignTrampleDamage ->
                    error("the pass-everything responder never attacks, but a trample request surfaced: $request")
                // ChooseDiscards is handled above; the other sized selections are cost/ability choices this
                // policy never reaches (it never casts or activates).
                is DecisionRequest.SizedSelection ->
                    error("the pass-everything responder never pays cost selections, but one surfaced: $request")
                is DecisionRequest.ChooseReplacement ->
                    error("the pass-everything responder never orders replacements: $request")
                is DecisionRequest.ChooseColor ->
                    error("the pass-everything responder never casts colour-choosing permanents: $request")
                is DecisionRequest.ChooseFromRevealed ->
                    error("the pass-everything responder never resolves a reveal effect: $request")
                is DecisionRequest.ChooseCostMode ->
                    error("the pass-everything responder never resolves a cost-then-draw spell: $request")
                is DecisionRequest.ChooseFromLibrary ->
                    error("the pass-everything responder never activates a library search: $request")
                is DecisionRequest.ChooseLibraryArrangement ->
                    error("the pass-everything responder never resolves a library look: $request")
                // CR 103.4/103.5: the passive policy keeps every hand at seven — so no bottoming ever
                // follows — but bottoms the lowest indices if a mulligan game is ever driven this way.
                is DecisionRequest.MulliganRequest -> keepAtSeven(request)
            }
        }

    // CR 103.4: keep the drawn hand; if somehow prompted to bottom, choose the lowest indices.
    private fun keepAtSeven(request: DecisionRequest.MulliganRequest): Decision =
        when (request) {
            is DecisionRequest.ChooseMulligan -> Decision.SingleSelect(request.id, DecisionRequest.ChooseMulligan.KEEP)
            is DecisionRequest.ChooseCardsToBottom -> Decision.MultiSelect(request.id, (0 until request.count).toList())
        }
}
