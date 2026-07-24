package dev.mtgplay.cli

import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption

/**
 * The pass-until / auto-collapse policy (P6.4 deliverable 3, from the P6.3 corpus brief): which
 * decisions the driver answers *for* the human without a prompt.
 *
 * These are driver conveniences, not engine changes (ADR-004 - the engine never auto-passes): every
 * auto-answer still flows through `advance` and enters the decision log, exactly as a typed answer
 * would. Two conveniences:
 *  - **pass-until**: a priority window (CR 117) whose only option is pass is auto-passed, so the
 *    ~490-passes-per-game reality does not drown the player in one-option menus;
 *  - **single-plan collapse**: a [DecisionRequest.ChoosePaymentPlan] with exactly one plan is
 *    auto-answered (the plan is forced anyway).
 *
 * Auto-pass **stops** (hands control back) exactly when the brief asks: the moment a non-pass option
 * exists (then it is not a pass-only window), whenever the stack is non-empty (so the player sees
 * what is about to resolve), and at any [stopSteps] on the player's own turn. The declare-attackers
 * and declare-blockers turn-based actions need no entry here - they surface as their own request
 * kinds ([DecisionRequest.DeclareAttackers]/[DeclareBlockers]), which always prompt.
 *
 * @property enabled whether pass-until is active (single-plan collapse is independent and always on).
 * @property stopSteps steps at which a pass-only window on the player's own turn still prompts;
 *   empty by default (combat declarations already always prompt).
 */
data class AutoPassPolicy(
    val enabled: Boolean = true,
    val stopSteps: Set<TurnStep> = emptySet(),
)

/**
 * The decision the driver should apply for [seat] without prompting, or `null` to prompt the human.
 * Collapses a sole payment plan and auto-passes a pass-only priority window per [policy].
 */
fun autoAnswer(
    request: DecisionRequest,
    state: GameState,
    policy: AutoPassPolicy,
): Decision? {
    val collapsed = collapseSinglePlan(request)
    if (collapsed != null) return collapsed
    return autoPass(request, state, policy)
}

/** Auto-answers a payment choice with its sole plan (CR 601.2g), or `null` when more than one exists. */
private fun collapseSinglePlan(request: DecisionRequest): Decision? =
    if (request is DecisionRequest.ChoosePaymentPlan && request.options.size == 1) {
        Decision.SingleSelect(request.id, 0)
    } else {
        null
    }

/** Auto-passes a pass-only priority window when the policy allows, or `null` to prompt the human. */
private fun autoPass(
    request: DecisionRequest,
    state: GameState,
    policy: AutoPassPolicy,
): Decision? {
    if (!policy.enabled || request !is DecisionRequest.ChooseAction) return null
    val passable = isPassOnly(request) && canAutoPass(state, request, policy)
    return if (passable) Decision.SingleSelect(request.id, passIndex(request)) else null
}

/** Whether a priority window's sole option is pass (CR 117.3d) - nothing else to do. */
private fun isPassOnly(request: DecisionRequest.ChooseAction): Boolean =
    request.options.size == 1 && request.options.single() is PriorityOption.Pass

/** Whether a pass-only window may be auto-passed: not while the stack is loaded, nor at a stop step. */
private fun canAutoPass(
    state: GameState,
    request: DecisionRequest.ChooseAction,
    policy: AutoPassPolicy,
): Boolean {
    if (state.sharedZones.stack.isNotEmpty()) return false
    val step = state.turn.step
    val ownTurn = state.turn.activePlayer == request.seat
    return !(ownTurn && step != null && step in policy.stopSteps)
}
