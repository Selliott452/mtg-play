package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.TargetSpec
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId

/*
 * Building the target-choice request (CR 601.2c), shared by the three flows that make one: a cast
 * (CR 601.2c, `PendingCastRequest.kt`), an activation (CR 602.2b, `ActivationGathering.kt`), and a
 * triggered ability being put on the stack (CR 603.3d, `TriggerTargeting.kt`). `FW-ABILTGT` established
 * that those three share one decision; `FW-MULTITGT` gives them a second one and this file is why they
 * still cannot disagree about which — the choice of request kind is made once, from the spec's count,
 * rather than three times from three call sites.
 */

/**
 * The target request for [spec] with [options] already enumerated, of whichever kind the spec's
 * cardinality calls for (`FW-MULTITGT`, docs/design/multi-target.md §4).
 *
 * **The dispatch is exactly "does this demand one target, or not"**, and both halves are real request
 * kinds rather than one kind with a flag:
 * - [dev.mtgplay.core.definition.TargetCount.Exactly]`(1)` — every targeting line that predates this
 *   framework — surfaces [DecisionRequest.ChooseTargets], a `SingleOptionSelection` answered with one
 *   index. Nothing about it changed, which is why every existing card, driver, replay log and wire
 *   message is untouched.
 * - Anything else surfaces [DecisionRequest.ChooseMultipleTargets], a `RangedSelection` answered with a
 *   distinct index subset. "Up to two" cannot be a `SingleOptionSelection`, because declining is not
 *   expressible as one index; "exactly two" cannot either, for the same reason in the other direction.
 *
 * Keeping them separate rather than folding every target choice into the ranged shape is the ADR-004
 * call and it is deliberate: a `MultiSelect` of arity exactly one is a strictly worse thing to hand an
 * agent than a `SingleSelect` — the agent has to discover the arity from the bounds — and converting
 * the single case would have rewritten every existing decision log for no behaviour a card can observe.
 *
 * Never called with an empty [options] list: [targetChoiceIsVacuous] settles that case without a
 * decision, and both request kinds refuse it in their `init`.
 */
internal fun targetRequest(
    id: DecisionRequestId,
    cardObjectId: ObjectId,
    card: CardRef,
    spec: TargetSpec,
    options: List<Target>,
): DecisionRequest {
    val bounds = targetChoiceBounds(spec, options.size)
    return if (bounds.first == 1 && bounds.last == 1) {
        DecisionRequest.ChooseTargets(id = id, cardObjectId = cardObjectId, card = card, options = options)
    } else {
        DecisionRequest.ChooseMultipleTargets(
            id = id,
            cardObjectId = cardObjectId,
            card = card,
            options = options,
            minimumCount = bounds.first,
            maximumCount = bounds.last,
        )
    }
}
