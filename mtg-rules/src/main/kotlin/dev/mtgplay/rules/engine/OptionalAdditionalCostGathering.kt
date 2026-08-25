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
 * The battlefield permanents [seat] controls that can pay [cost] (CR 601.2h). For
 * [OptionalAdditionalCost.Bargain] (CR 702.166a) that is the union of their artifacts, their
 * enchantments, and their tokens, in battlefield order. Control is ownership in the MVP pool.
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
    return if (optionalCostPayableWith(state, seat, cost).isEmpty()) false else null
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
 * Records the permanents chosen to pay an announced optional additional cost (CR 601.2b/h) on the open
 * [PendingCast] and suspends for the next decision. They are sacrificed only when the cast executes,
 * atomically with everything else.
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
 * Stage CR 601.2b/h — the optional additional cost itself: sacrifices the permanents chosen to pay an
 * announced cost (CR 701.17), or does nothing when it was declined or absent (the settled list is
 * empty). The permanents were chosen legally while gathering (ADR-005), so a missing one is an engine
 * defect and [sacrificePermanents] fails loudly.
 *
 * Paid **after** the mana (CR 601.2g precedes CR 601.2h), beside the intrinsic sacrifice cost and for
 * the same reason: a permanent tapped for mana by the plan may be the one sacrificed
 * (docs/design/mana-payment.md §2.2).
 */
internal fun payOptionalAdditionalCost(
    state: GameState,
    cast: PendingCast,
): GameState {
    val toSacrifice =
        cast.optionalCostObjects
            ?: error("CR 601.2b: the optional additional cost of ${cast.cardObjectId} was not settled before payment")
    return sacrificePermanents(state, cast.caster, toSacrifice)
}
