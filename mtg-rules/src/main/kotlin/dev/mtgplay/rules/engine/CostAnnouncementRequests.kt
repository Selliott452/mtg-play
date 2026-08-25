package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingCast
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId

/*
 * The two **cost-announcement** requests of a gathering cast (CR 601.2b): kicker's yes/no and X's value.
 *
 * Split out of `PendingCastRequest.kt` only when that file reached detekt's per-file function budget —
 * the budget doing its job, since a wave that adds two optional-cost frameworks adds a builder each.
 * The seam is real rather than arbitrary: everything here is announced *before* costs are locked in
 * (CR 601.2f) and reads no target, while the file it left builds the target and payment requests that
 * depend on those announcements.
 */

/**
 * The CR 601.2b kicker announcement (CR 702.33a): a plain yes/no, because declining is always legal and
 * the announcement is only surfaced at all when the kicked cost is affordable ([kickerAffordable]) — so
 * both answers lead somewhere payable, which is what a yes/no requires (ADR-005).
 *
 * The prompt names the cost, because "pay the kicker?" is not answerable without knowing what it costs
 * and the request carries no other price.
 */
internal fun kickerAnnouncementRequest(
    cast: PendingCast,
    definition: SpellDefinition,
    card: CardRef,
    id: DecisionRequestId,
): DecisionRequest.ChooseYesNo =
    DecisionRequest.ChooseYesNo(
        id = id,
        prompt = "Pay the kicker cost ${definition.kicker?.render()} for ${card.name}?",
        cardObjectId = cast.cardObjectId,
        card = card,
    )

/**
 * The CR 601.2b announcement of a variable cost (CR 107.3b), whose options are the values this seat can
 * actually pay for (`XCost.kt`).
 *
 * Two inputs make the bound exact, and both are settled by the time this branch is reached. The kicker
 * answer, one branch above, is part of the cost each candidate is priced against. And the reservation is
 * the **identical** set the [DecisionRequest.ChoosePaymentPlan] below will use — which is the whole
 * reason the announcement is settled here rather than at CR 601.2b's printed position, above the target
 * stage (see the file header).
 */
internal fun xAnnouncementRequest(
    state: GameState,
    cast: PendingCast,
    definition: SpellDefinition,
    card: CardRef,
    id: DecisionRequestId,
): DecisionRequest.ChooseXValue =
    DecisionRequest.ChooseXValue(
        id = id,
        cardObjectId = cast.cardObjectId,
        card = card,
        values =
            xValueOptions(
                state,
                cast.caster,
                cast.subject(definition),
                // Non-null in this branch, but a cross-module property the compiler will not smart-cast;
                // `?: false` is the same value, exactly as `chosenModes.orEmpty()` is above.
                kicked = cast.kicked ?: false,
                reserved = sacrificeSourcesAmong(state, cast.sacrificedThisCast()),
            ),
    )
