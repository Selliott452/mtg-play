package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.AdditionalCost
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingCast
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId

/*
 * The pending decision a cast in progress is waiting on (CR 601.2), split from PendingDecision.kt so
 * each file stays within its function budget. The gathering order is fixed: modes (601.2b), targets
 * (601.2c), then the additional-exile / sacrifice / additional-discard cost selections (601.2b/h), then
 * the **kicker** announcement and the **value of X** (601.2b), then the payment plan (601.2g) — always
 * surfaced, even with a single plan, so replay logs stay canonical (P2.1).
 *
 * Modes come first because CR 601.2b puts them first, and `FW-MODAL` is the packet that made the
 * ordering observable: until then no card had modes, so the stage was a documented no-op.
 *
 * **The two cost announcements sit last, and that is a deliberate deviation from CR 601.2b's printed
 * order**, which announces kicker and X *before* targets are chosen. Both are bounded by affordability
 * — a kicker is offered only when the kicked cost is payable, and a value of X only when that value's
 * total cost is payable — and affordability is only exact once the sibling selections that reserve mana
 * sources (a sacrifice additional cost) are settled. Announcing at CR 601.2b's position would bound
 * them against `minimalSacrificeReservation` while the payment enumeration uses the exact,
 * choice-aware one, which is the enumerated-then-unpayable defect ADR-005 forbids
 * (docs/design/mana-payment.md §2.3).
 *
 * The deviation is unobservable across the whole *encoded* pool, because nothing in it makes a target's
 * legality depend on a kicker or on X. It becomes observable for a card printing "X target creatures"
 * or a kicker that adds a target, and such a card must move the announcements back above the target
 * stage and take the weaker reservation with them — the same trade `FW-ADDSAC` recorded for the
 * sacrifice cost. Recorded here rather than discovered later.
 *
 * **`W8-C` found that card, and it is in the gauntlet.** Gorilla Shaman's "`{X}{X}{1}`: Destroy target
 * noncreature artifact **with mana value X**" makes the target restriction a function of the announced
 * value, so X must be settled before CR 601.2c rather than after it. That packet dropped the card rather
 * than reorder this file's gathering unilaterally — the reorder costs every *other* cast the exact
 * reservation, which is a trade the whole payment model pays for one card — and it needs the announcement
 * on the **activation** path anyway, which has no `chosenX` at all. Both halves are on the same packet
 * when it is taken; see `mtg-cards/BurnAndRemoval.kt`.
 *
 * Ride's End is the mirror case and it does *not* force a reorder: its **cost** depends on its chosen
 * target rather than its target depending on a cost announcement, so the existing order (targets, then
 * announcements, then payment) is exactly right for it (`FW-TGTCOND`, `TargetConditionalCost.kt`).
 *
 * Kicker precedes X because CR 601.2b prints it that way ("alternative or additional costs … then …
 * a variable cost"), and because the dependency runs that way too: the affordable values of X are the
 * values affordable *given* the kicker announcement, so X cannot be bounded until kicker is settled.
 */

/**
 * The request the open [cast] is waiting on (CR 601.2). A pure function of the state (ADR-004): each
 * gathered-so-far choice on [cast] settles one stage, and this re-derives the next unanswered one.
 */
internal fun pendingCastRequest(
    state: GameState,
    cast: PendingCast,
): DecisionRequest {
    val card =
        objectInZone(state, cast.caster, cast.source, cast.cardObjectId)
            ?: error("CR 601.2: pending cast's card ${cast.cardObjectId} is not in ${cast.caster}'s ${cast.source}")
    val definition = spellDefinitionOf(state, card.card)
    val id = DecisionRequestId(cast.caster, state.player(cast.caster).decisionsAnswered)
    return when {
        // CR 601.2b: modes first, and the precedence is load-bearing rather than ceremonial — a modal
        // card's modes may target different *kinds* of object (Blue Elemental Blast counters a spell or
        // destroys a permanent), so the targets branch below has no enumeration to run until the mode is
        // settled. Only choosable modes are offered (ADR-005).
        cast.chosenModes == null ->
            DecisionRequest.ChooseModes(
                id = id,
                cardObjectId = cast.cardObjectId,
                card = card.card,
                options =
                    castableModes(state, definition, cast.caster, Chooser.Spell(cast.cardObjectId))
                        .map { DecisionRequest.ChooseModes.Option(it, definition.modes[it].text) },
            )
        // CR 601.2c: then targets, enumerated against the spec the settled mode put in force. The modes
        // are non-null in this branch, but they are a cross-module property so the compiler will not
        // smart-cast them; `orEmpty()` is the same value, and a non-modal card's is empty anyway.
        cast.chosenTargets == null -> targetsRequestFor(state, cast, definition, card.card, id)
        // CR 601.2b: then any additional "exile N other cards" cost selection (escape).
        cast.additionalExileCost == null -> chooseCardsToExileRequest(state, cast, card.card, id)
        // CR 601.2h: then any non-mana sacrifice cost selection (Fireblast, Lava Dart).
        cast.sacrificeCost == null -> chooseSacrificesRequest(state, cast, card.card, id)
        // CR 601.2b: then any additional discard cost selection (Grab the Prize).
        cast.additionalDiscard == null -> chooseDiscardForCostRequest(state, cast, definition, card.card, id)
        // CR 601.2b: then any intrinsic sacrifice additional cost selection (Eviscerator's Insight).
        cast.additionalSacrifice == null -> chooseSacrificeForCostRequest(state, cast, definition, card.card, id)
        // CR 601.2b/702.33a: then the optional kicker announcement, surfaced only when the kicked cost
        // is affordable — so both answers are legal, which is what a yes/no requires (ADR-005).
        cast.kicked == null -> kickerAnnouncementRequest(cast, definition, card.card, id)
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
                        sacrificeSourcesAmong(state, cast.additionalSacrifice.orEmpty()),
                    ),
            )
        }
    }
}

/**
 * The CR 601.2c target request for the open [cast], enumerated against the spec the settled mode put in
 * force (`FW-MODAL`).
 *
 * The card is still in its source zone while gathering, so naming it as the [Chooser.Spell] excludes
 * nothing from the stack — and naming it is what makes this enumeration equal the one `establishTargets`
 * recomputes once the card is on the stack under a fresh id.
 *
 * The options are then narrowed by [affordableTargetOptions], which is the identity for every card that
 * does not price itself off its own targets — all but Ride's End (`FW-TGTCOND`). Narrowing here rather
 * than in `legalTargets` is deliberate: CR 115 legality is unchanged and the CR 601.2c re-validation still
 * tests membership in the *unfiltered* enumeration, so the filter can only ever remove an option the
 * caster could not have paid for, never make a chosen one illegal.
 */
private fun targetsRequestFor(
    state: GameState,
    cast: PendingCast,
    definition: SpellDefinition,
    card: CardRef,
    id: DecisionRequestId,
): DecisionRequest {
    val spec = effectiveTargetSpec(definition, cast.chosenModes.orEmpty())
    // Two independent narrowings, and neither subsumes the other: `announceableTargets`
    // applies board-derived targeting *requirements* (CR 601.2c — a Flagbearer must be chosen
    // if able), and `affordableTargetOptions` drops choices the caster could not then pay for
    // (`FW-TGTCOND`). Offering a target that fails either is an enumerated-but-illegal action.
    val legal = announceableTargets(state, spec, cast.caster, Chooser.Spell(cast.cardObjectId))
    return targetRequest(
        id = id,
        cardObjectId = cast.cardObjectId,
        card = card,
        spec = spec,
        options = affordableTargetOptions(state, cast.caster, cast.subject(definition), spec, legal),
    )
}

// CR 601.2b/702.139a: every card in the source zone other than the one being cast is exilable (escape).
private fun chooseCardsToExileRequest(
    state: GameState,
    cast: PendingCast,
    card: CardRef,
    id: DecisionRequestId,
): DecisionRequest.ChooseCardsToExile {
    val permission =
        cast.castingPermission ?: error("CR 601.2b: an additional exile cost requires a casting permission")
    return DecisionRequest.ChooseCardsToExile(
        id = id,
        cardObjectId = cast.cardObjectId,
        card = card,
        options =
            objectsInZone(state, cast.caster, cast.source)
                .filter { it.id != cast.cardObjectId }
                .map { DecisionRequest.ChooseCardsToExile.Option(it.id, it.card) },
        count = permission.additionalExileCount,
    )
}

// CR 601.2h: every matching permanent the caster controls is a sacrifice option (Fireblast, Lava Dart).
private fun chooseSacrificesRequest(
    state: GameState,
    cast: PendingCast,
    card: CardRef,
    id: DecisionRequestId,
): DecisionRequest.ChooseSacrifices {
    val requirement =
        cast.castingPermission?.sacrifice ?: error("CR 601.2h: a sacrifice cost requires a casting permission")
    return DecisionRequest.ChooseSacrifices(
        id = id,
        cardObjectId = cast.cardObjectId,
        card = card,
        options =
            sacrificeableFor(state, cast.caster, requirement)
                .map { DecisionRequest.ChooseSacrifices.Option(it.id, it.card) },
        count = requirement.count,
    )
}

// CR 601.2b: every matching permanent the caster controls is an additional-sacrifice-cost option
// (Eviscerator's Insight's "an artifact or creature", Raze's "a land"). The card being cast is in the
// hand or the graveyard, never on the battlefield, so it excludes nothing from its own option list.
private fun chooseSacrificeForCostRequest(
    state: GameState,
    cast: PendingCast,
    definition: dev.mtgplay.core.definition.SpellDefinition,
    card: CardRef,
    id: DecisionRequestId,
): DecisionRequest.ChooseSacrificesForCost {
    val additional =
        definition.additionalCost as? AdditionalCost.Sacrifice
            ?: error("CR 601.2b: an additional sacrifice cost requires a sacrifice additional cost")
    return DecisionRequest.ChooseSacrificesForCost(
        id = id,
        cardObjectId = cast.cardObjectId,
        card = card,
        options =
            sacrificeableMatching(state, cast.caster, additional.filter)
                .map { DecisionRequest.ChooseSacrificesForCost.Option(it.id, it.card) },
        count = additional.count,
    )
}

// CR 601.2b: every card in the caster's hand except the one being cast is a discard-cost option (Grab the Prize).
private fun chooseDiscardForCostRequest(
    state: GameState,
    cast: PendingCast,
    definition: dev.mtgplay.core.definition.SpellDefinition,
    card: CardRef,
    id: DecisionRequestId,
): DecisionRequest.ChooseCardsToDiscardForCost {
    val additional =
        definition.additionalCost as? AdditionalCost.DiscardCards
            ?: error("CR 601.2b: an additional discard cost requires a discard additional cost")
    return DecisionRequest.ChooseCardsToDiscardForCost(
        id = id,
        cardObjectId = cast.cardObjectId,
        card = card,
        options =
            state
                .player(cast.caster)
                .hand
                .filter { it.id != cast.cardObjectId }
                .map { DecisionRequest.ChooseCardsToDiscardForCost.Option(it.id, it.card) },
        count = additional.count,
    )
}

/**
 * The CR 601.2b kicker announcement (CR 702.33a): a plain yes/no, because declining is always legal and
 * the announcement is only surfaced at all when the kicked cost is affordable ([kickerAffordable]) — so
 * both answers lead somewhere payable, which is what a yes/no requires (ADR-005).
 *
 * The prompt names the cost, because "pay the kicker?" is not answerable without knowing what it costs
 * and the request carries no other price.
 */
private fun kickerAnnouncementRequest(
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
private fun xAnnouncementRequest(
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
                reserved = sacrificeSourcesAmong(state, cast.additionalSacrifice.orEmpty()),
            ),
    )
