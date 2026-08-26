package dev.mtgplay.rules.engine

import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest

/*
 * The two **subset-shaped** decision appliers whose answer is bounded by something other than an exact
 * size: a ranged selection (CR 601.2c — "up to two target creatures", CR 609.4's untargeted permanent
 * choice) and a summed-weight selection (CR 601.2b/701.60a — collect evidence).
 *
 * Split out of `DecisionApplication.kt` rather than suppressed when `W9-B`'s sixth decision family
 * pushed that file past detekt's function budget. The seam is a real one and not merely arithmetic: both
 * appliers here take a *set* whose cardinality is the answerer's to choose, where every applier left
 * behind takes either a single index or a fixed-size set. Both `when`s stay exhaustive with no `else`,
 * so a new leaf in either family still breaks compilation.
 */

/**
 * Applies one ranged subset selection — a multi-target choice (CR 601.2c) or an untargeted
 * mid-resolution permanent selection (CR 609.4) — dispatching by kind. The indices are already
 * validated as distinct, in range, and of a size within the request's bounds (ADR-004), so mapping them
 * straight onto options is safe.
 *
 * The chosen targets go to the same three-flow applier its single-target sibling uses
 * (`SingleOptionApplication.kt`): a cast, an activation, or a trigger placement, told apart by the open
 * pending record. The two request kinds differ only in how an agent *says* which targets it picked.
 */
internal fun applyRangedSelection(
    state: GameState,
    request: DecisionRequest.RangedSelection,
    decision: Decision,
): AdvanceResult {
    check(decision is Decision.MultiSelect) { "unreachable: decision shape was validated against the request" }
    return when (request) {
        is DecisionRequest.ChooseMultipleTargets ->
            applyChosenTargetList(state, decision.indices.map { request.options[it] }, request)
        // CR 609.4: an untargeted mid-resolution selection of battlefield permanents — the same ranged
        // shape, but the answer names permanents to act on rather than targets to record.
        is DecisionRequest.ChoosePermanentsToAffect ->
            applyPermanentSelection(state, decision.indices.map { request.options[it].objectId })
    }
}

/**
 * Applies one summed-weight subset selection (CR 601.2b, CR 701.60a) — collect evidence — dispatching by
 * kind. The indices are already validated as distinct, in range, and *summing* to at least the request's
 * threshold (ADR-004), so mapping them straight onto options is safe.
 */
internal fun applySummedSelection(
    state: GameState,
    request: DecisionRequest.SummedSelection,
    decision: Decision,
): AdvanceResult {
    check(decision is Decision.MultiSelect) { "unreachable: decision shape was validated against the request" }
    return when (request) {
        is DecisionRequest.ChooseEvidence ->
            applyChosenOptionalCostObjects(state, decision.indices.map { request.options[it].objectId })
    }
}
