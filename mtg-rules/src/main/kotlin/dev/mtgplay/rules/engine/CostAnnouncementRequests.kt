package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.GameObject
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

/**
 * The tail of [pendingCastRequest]: the optional costs, the two CR 601.2b announcements, the
 * non-consuming naming, and the CR 601.2g payment plan once all of them are settled.
 *
 * Split out only so the chain stays inside detekt's complexity budget — the order is a **continuation**
 * of the one above and must not be reasoned about separately. Every gate here sits *after* the
 * mandatory cost selections because none of them reserves anything, so none constrains what follows.
 */
internal fun optionalCostsOrPaymentRequest(
    state: GameState,
    cast: PendingCast,
    definition: SpellDefinition,
    card: GameObject,
    id: DecisionRequestId,
): DecisionRequest =
    when {
        // CR 601.2b/702.166a: the optional additional cost's announcement and, if taken, its object
        // selection. The pair sits together because a declined announcement must settle the selection
        // too, and the pair reads as a pair.
        cast.optionalCostTaken == null -> optionalCostAnnouncementRequest(state, cast, definition, card.card, id)
        cast.optionalCostObjects == null -> chooseOptionalCostObjectsRequest(state, cast, definition, card.card, id)
        // CR 601.2b/702.33a: then the optional kicker announcement, surfaced only when the kicked cost
        // is affordable — so both answers are legal, which is what a yes/no requires (ADR-005).
        cast.kicked == null -> kickerAnnouncementRequest(cast, definition, card.card, id)
        // CR 601.2b: then any **non-consuming** additional cost's naming (`W9-D`, Monstrous Emergence).
        // It sits beside the mandatory costs because it is one, and after them because it reserves
        // nothing and so constrains nothing that follows.
        cast.costPowerSource == null -> choosePowerSourceRequest(state, cast, definition, card.card, id)
        // CR 601.2b/107.3b: then the value of X, bounded by what this seat can actually pay (`FW-X`).
        cast.chosenX == null -> xAnnouncementRequest(state, cast, definition, card.card, id)
        // CR 601.2g: finally the payment plan for the (possibly alternative) mana cost.
        else -> {
            // CR 601.2f: the same shared function legality and the pipeline use, with the card still
            // in its source zone and therefore excluded from its own zone counts (CR 601.2a) — which
            // is what makes this cost equal the one `determineTotalCost` recomputes at execution.
            // CR 601.2b: both announcements are settled by now, so this is the cost the cast will
            // actually charge — and the identical expression `determineTotalCost` recomputes at
            // execution.
            val cost = totalCost(state, cast.caster, cast.subject(definition), cast.announcements())
            DecisionRequest.ChoosePaymentPlan(
                id = id,
                cardObjectId = cast.cardObjectId,
                card = card.card,
                cost = cost,
                // A permanent already chosen for the sacrifice additional cost is excluded from
                // funding the mana **only** when it produces mana by being sacrificed — spending it
                // would consume it before the cost's own sacrifice. Tapping a chosen land for mana
                // and then sacrificing it is legal and stays enumerated
                // (docs/design/mana-payment.md §2.2).
                options =
                    enumeratePaymentPlans(
                        state,
                        cast.caster,
                        cost,
                        sacrificeSourcesAmong(state, cast.sacrificedThisCast()),
                    ),
            )
        }
    }
