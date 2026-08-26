package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.definition.OptionalAdditionalCost
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingCast
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.effect.exileCardFromGraveyard
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/*
 * The **optional additional cost with a chosen object** (CR 601.2b) — bargain (CR 702.166). Additive
 * (`FW-BARGAIN`).
 *
 * Two gathering stages rather than one, and the pairing is what makes this cost family its own:
 *
 * 1. the **announcement** (CR 601.2b), a yes/no surfaced only when the cost could actually be paid, so
 *    both answers lead somewhere legal (ADR-005) — kicker's rule, applied to a cost that consumes a
 *    permanent instead of mana;
 * 2. the **selection**, opened only by a "yes", enumerating the permanents that may pay it.
 *
 * A "no" settles both stages at once (the announcement to `false`, the object list to empty), so a
 * declining cast asks exactly one question and a board that cannot bargain asks none. That is the
 * `LibrarySearch.optional` discipline stated the other way round: **declining is an enumerated answer,
 * not an absent request** — it is index 0 of a yes/no that is always surfaced when the choice is real.
 */

/**
 * The objects [seat] may spend on [cost] (CR 601.2b/h) — the option list of its selection stage, and
 * **not** the same zone for every member. For [OptionalAdditionalCost.Bargain] (CR 702.166a) it is the
 * union of their artifacts, their enchantments, and their tokens, in battlefield order (control is
 * ownership in the MVP pool); for [OptionalAdditionalCost.CollectEvidence] (CR 701.60a) it is the whole
 * of their graveyard, in graveyard order.
 *
 * **Tokenhood is not a card type**, so it cannot be a [dev.mtgplay.core.definition.SacrificeFilter]
 * axis: a token is a non-card game object (CR 111.1), tested here the way the whole engine tests it —
 * `definitions[card] is TokenDefinition` ([isToken]) — which is stable across the CR 400.7 rebirths.
 * The three arms are a genuine union: an artifact token matches twice and is offered once, and an
 * ordinary creature matches none of them and is not offered at all.
 */
internal fun optionalCostPayableWith(
    state: GameState,
    seat: PlayerId,
    cost: OptionalAdditionalCost,
): List<GameObject> =
    when (cost) {
        OptionalAdditionalCost.Bargain ->
            state.sharedZones.battlefield.filter { obj ->
                val printed = state.definitions[obj.card]?.characteristics
                val types = printed?.cardTypes ?: emptySet()
                obj.owner == seat &&
                    (CardType.ARTIFACT in types || CardType.ENCHANTMENT in types || isToken(state, obj))
            }
        // CR 701.60a: "exile cards ... from your graveyard" — every card there is spendable, unfiltered.
        // That the list carries no filter is the point: a collect-evidence answer is constrained by a
        // *sum*, and the constraint travels in the request rather than in this list (`SummedSelection`).
        is OptionalAdditionalCost.CollectEvidence -> state.player(seat).graveyard
    }

/**
 * The mana value of the graveyard object [obj] for collect evidence (CR 202.3, CR 701.60a): its
 * **printed** cost's, or zero for a card with none (a land) and for a definition the pool does not
 * carry.
 *
 * Printed rather than announced, and that is a rule rather than a shortcut: CR 202.3b's "X is the
 * announced value" holds only while a spell is on the stack, and these cards are in a graveyard, where
 * an unvalued `{X}` counts as zero (CR 107.3e).
 */
internal fun evidenceManaValue(
    state: GameState,
    obj: GameObject,
): Int = state.definitions[obj.card]?.characteristics?.manaValue ?: 0

/**
 * Whether [seat] could actually pay [cost] right now (CR 601.2b) — the gate on the *announcement*, and
 * the reason the two members cannot share one `isNotEmpty()` test.
 *
 * Bargain needs one spendable permanent, so a non-empty option list is exactly the question. Collect
 * evidence needs the option list's mana values to **sum** to its amount, and a graveyard of four lands
 * is a long non-empty list that pays nothing. Testing emptiness there would offer a "yes" whose
 * selection stage has no legal answer, which is the enumerated-then-unpayable defect ADR-005 forbids.
 */
internal fun optionalCostIsPayable(
    state: GameState,
    seat: PlayerId,
    cost: OptionalAdditionalCost,
): Boolean {
    val payableWith = optionalCostPayableWith(state, seat, cost)
    return when (cost) {
        OptionalAdditionalCost.Bargain -> payableWith.isNotEmpty()
        is OptionalAdditionalCost.CollectEvidence ->
            payableWith.sumOf { evidenceManaValue(state, it) } >= cost.amount
    }
}

/**
 * The settled announcement a cast starts with for [definition]'s optional additional cost (CR 601.2b):
 * `null` — "still to be announced" — when the card prints one *and* the seat can pay it, and `false`
 * otherwise.
 *
 * **`false` covers two different situations and settles both silently**, which is correct in each. A
 * card with no such cost has nothing to announce. A card with one that the board cannot pay has only a
 * "no" available, and a yes/no with one legal answer is not a decision — surfacing it would put an
 * index in the action space whose "yes" dead-ends mid-cast, which is the ADR-005 defect the kicker
 * announcement is gated for.
 *
 * Note the cost never gates *castability*: an optional cost declined is a legal cast, so nothing in
 * `CastLegality.kt` consults this.
 */
internal fun initialOptionalCostAnnouncement(
    state: GameState,
    seat: PlayerId,
    definition: SpellDefinition,
): Boolean? {
    val cost = definition.optionalAdditionalCost ?: return false
    return if (optionalCostIsPayable(state, seat, cost)) null else false
}

/**
 * Records the CR 601.2b announcement of an optional additional cost on the open [PendingCast] and
 * suspends for the next decision. A declined announcement settles the object selection empty in the
 * same step, so the cast never pauses for a choice among nothing.
 */
internal fun applyOptionalCostAnnouncement(
    state: GameState,
    take: Boolean,
): AdvanceResult {
    val cast = state.pendingCast ?: error("no cast is gathering decisions")
    require(cast.optionalCostTaken == null) {
        "CR 601.2b: this cast's optional additional cost is already announced"
    }
    val settled =
        cast.copy(
            optionalCostTaken = take,
            optionalCostObjects = if (take) null else persistentListOf(),
        )
    return pauseForNextCastDecision(state.copy(pendingCast = settled))
}

/**
 * Records the objects chosen to pay an announced optional additional cost (CR 601.2b/h) on the open
 * [PendingCast] and suspends for the next decision — bargain's one permanent, or the graveyard cards
 * whose mana values collect the evidence. They are consumed only when the cast executes, atomically
 * with everything else.
 */
internal fun applyChosenOptionalCostObjects(
    state: GameState,
    objectIds: List<ObjectId>,
): AdvanceResult {
    val cast = state.pendingCast ?: error("no cast is gathering decisions")
    require(cast.optionalCostObjects == null) {
        "CR 601.2b: this cast's optional additional cost objects are already chosen"
    }
    return pauseForNextCastDecision(
        state.copy(pendingCast = cast.copy(optionalCostObjects = objectIds.toPersistentList())),
    )
}

/**
 * Stage CR 601.2b/h — the optional additional cost itself: consumes the objects chosen to pay an
 * announced cost, or does nothing when it was declined or absent (the settled list is empty).
 *
 * **How they are consumed belongs to the cost, not to this stage**, and the two members differ in zone
 * as well as in verb: bargain sacrifices a permanent (CR 701.17), collect evidence exiles graveyard
 * cards (CR 701.3a, CR 701.60a). Folding them behind one call would put two genuinely different moves
 * behind one name, which is the objection `ExileFromGraveyard.kt` already records for the exile
 * primitives themselves. A declined or absent cost settles the list empty, so [definition] is consulted
 * only when there is something to consume.
 *
 * The objects were chosen legally while gathering (ADR-005): a missing permanent is an engine defect and
 * [sacrificePermanents] fails loudly, while a missing graveyard card is not, for the reason
 * [exileCardFromGraveyard] documents.
 *
 * Paid **after** the mana (CR 601.2g precedes CR 601.2h), beside the intrinsic sacrifice cost and for
 * the same reason: a permanent tapped for mana by the plan may be the one sacrificed
 * (docs/design/mana-payment.md §2.2).
 */
internal fun payOptionalAdditionalCost(
    state: GameState,
    cast: PendingCast,
    definition: SpellDefinition,
): GameState {
    val chosen =
        cast.optionalCostObjects
            ?: error("CR 601.2b: the optional additional cost of ${cast.cardObjectId} was not settled before payment")
    if (chosen.isEmpty()) return state
    return when (definition.optionalAdditionalCost) {
        null ->
            error("CR 601.2b: ${cast.cardObjectId} paid an optional additional cost its definition does not print")
        OptionalAdditionalCost.Bargain -> sacrificePermanents(state, cast.caster, chosen)
        // CR 701.60a: collecting evidence exiles the chosen cards from the caster's own graveyard.
        is OptionalAdditionalCost.CollectEvidence -> chosen.fold(state, ::exileCardFromGraveyard)
    }
}

/**
 * Every permanent this cast has already committed to sacrificing (CR 601.2b/h) — the *intrinsic*
 * additional cost's choices and the *optional* one's, together.
 *
 * The two are separate fields because they are separate costs (a card may print both), but the mana
 * enumeration cares about neither distinction: what it must not do is offer a plan that spends a
 * permanent one of them is about to consume. Reading only one of the two would leave the other's
 * choice enumerable as a mana source and then fail loudly at payment — the enumerate-then-unpayable
 * defect ADR-005 forbids. `sacrificeSourcesAmong` narrows this to the permanents that produce mana
 * *by* being sacrificed, so a land tapped for mana and then sacrificed stays legal and stays offered
 * (docs/design/mana-payment.md §2.2).
 */
internal fun PendingCast.sacrificedThisCast(): List<ObjectId> =
    additionalSacrifice.orEmpty() + optionalCostObjects.orEmpty()
