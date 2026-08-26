package dev.mtgplay.rules.engine

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.definition.AdditionalCost
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.ChosenPowerSource
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingCast
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/*
 * The whole cast-side pipeline for the **non-consuming** additional cost (CR 601.2b, `W9-D`): its option
 * pool, its payability gate, its initial settlement, its request, and the applier for its answer.
 *
 * Its own file rather than four additions spread across `CastLegality.kt`, `CastGathering.kt` and
 * `PendingCastRequest.kt`, and the grouping is real rather than a budget dodge — though all three of those
 * files were at detekt's function or complexity budget, which is what asked the question. Every other
 * cast-side cost *spends* something, so its pool, its gate and its applier are each a variation on the
 * neighbours around them in those files. This one spends nothing: its gate is a bare emptiness test with
 * no mana reservation, its applier moves no object, and its pool spans two zones at once. Keeping the five
 * pieces together is what makes that consistent story readable in one place.
 */

/**
 * The things that may be named to pay a card's **non-consuming** additional cost (Monstrous Emergence's
 * "choose a creature you control or reveal a creature card from your hand" — CR 601.2b), in the order the
 * request offers them: the caster's battlefield creatures first, then the creature cards in their hand.
 *
 * The card being cast is excluded from the hand half and only from it — it is in the hand while the cast is
 * gathering (CR 601.2a), and CR 601.2b's "a creature card from your hand" cannot mean the spell itself,
 * which is no longer a card in hand at all. Nothing needs excluding from the battlefield half, where the
 * card being cast has never been.
 *
 * "Creature" is [isCreature] on the battlefield — the CR 302.1 question the layer system answers — and the
 * **printed** card types in hand (CR 109.3): no continuous effect in this pool reaches a hand, so a hand
 * card's types are its printed ones and asking the layer system about an object that is not on the
 * battlefield would fail.
 *
 * Empty for a definition with no such cost, which is what makes [powerSourceCostSatisfiable] a single
 * emptiness test.
 */
internal fun powerSourceOptions(
    state: GameState,
    seat: PlayerId,
    definition: SpellDefinition,
    castObjectId: ObjectId,
    source: CastSource,
): List<PowerSourceOption> {
    if (definition.additionalCost != AdditionalCost.ChooseCreatureOrRevealCreatureCard) return emptyList()
    val controlled =
        state.sharedZones.battlefield
            .filter { it.owner == seat && isCreature(state, it) }
            .map { PowerSourceOption(ChosenPowerSource.ChosenCreature(it.id), it.card, effectivePower(state, it.id)) }
    val revealable =
        state
            .player(seat)
            .hand
            .filter { !(source == CastSource.HAND && it.id == castObjectId) }
            .mapNotNull { card -> revealableOption(state, card.card) }
    return controlled + revealable
}

/**
 * The naming option a hand [card] offers, or `null` when it is not a creature card (CR 109.3).
 *
 * Fails loudly for a creature card with no printed power/toughness box, which is a contradiction the
 * definition registry would have to have produced (CR 208.1); `0` is not silently substituted.
 */
private fun revealableOption(
    state: GameState,
    card: CardRef,
): PowerSourceOption? {
    val creature =
        state.definitions[card]
            ?.characteristics
            ?.takeIf { CardType.CREATURE in it.cardTypes }
            ?: return null
    val power =
        creature.powerToughness?.power
            ?: error("CR 208.1: creature card ${card.name} has no printed power")
    return PowerSourceOption(ChosenPowerSource.RevealedCard(card), card, power)
}

/**
 * One thing that may be named to pay a non-consuming additional cost: what naming it produces, its printed
 * identity for display, and the power it would supply right now (CR 613 for a battlefield creature,
 * CR 109.3 for a hand card).
 */
internal data class PowerSourceOption(
    val source: ChosenPowerSource,
    val card: CardRef,
    val power: Int,
)

/**
 * Whether a card's **non-consuming** additional cost (CR 601.2b) can be paid: there is at least one thing
 * to name. Trivially true when the definition has no such cost.
 *
 * The whole payability question, because nothing is spent — a cost that only points at something is
 * payable exactly when there is something to point at, and it can never compete with the mana payment for
 * a resource. That is why it has no counterpart to [minimalSacrificeReservation], and the absence is the
 * clearest single statement of what makes this cost shape different.
 */
internal fun powerSourceCostSatisfiable(
    state: GameState,
    seat: PlayerId,
    definition: SpellDefinition,
    castObjectId: ObjectId,
    source: CastSource,
): Boolean =
    definition.additionalCost != AdditionalCost.ChooseCreatureOrRevealCreatureCard ||
        powerSourceOptions(state, seat, definition, castObjectId, source).isNotEmpty()

/**
 * The initial value of [PendingCast.costPowerSource] for a cast of [definition] (CR 601.2b): `null` — a
 * naming is due — for a card carrying the cost, and an empty list for every other card, which settles the
 * stage at once.
 *
 * Read off the definition rather than off the casting permission, because CR 702.34a's "and any additional
 * costs" means a permission cast pays a card's additional costs too.
 */
internal fun initialPowerSourceSettlement(definition: SpellDefinition): PersistentList<ChosenPowerSource>? =
    if (definition.additionalCost == AdditionalCost.ChooseCreatureOrRevealCreatureCard) {
        null
    } else {
        persistentListOf()
    }

/**
 * The CR 601.2b naming request for the open [cast]: everything the caster may name, in the order
 * [powerSourceOptions] produces. The pool is derived by the same function cast legality used, so a cast
 * that was enumerated always has at least one option here (ADR-005).
 */
internal fun choosePowerSourceRequest(
    state: GameState,
    cast: PendingCast,
    definition: SpellDefinition,
    card: CardRef,
    id: DecisionRequestId,
): DecisionRequest.ChooseCostPowerSource =
    DecisionRequest.ChooseCostPowerSource(
        id = id,
        cardObjectId = cast.cardObjectId,
        card = card,
        options =
            powerSourceOptions(state, cast.caster, definition, cast.cardObjectId, cast.source)
                .map { DecisionRequest.ChooseCostPowerSource.Option(it.source, it.card, it.power) },
    )

/**
 * Records what the caster named to pay a **non-consuming** additional cost (Monstrous Emergence —
 * CR 601.2b) on the open [PendingCast] and suspends for the next stage.
 *
 * Nothing is spent here and nothing will be spent when the cast executes: the chosen creature stays on the
 * battlefield and the revealed card stays in hand. So unlike [applyChosenAdditionalSacrifice] this reserves
 * nothing against the payment plan that is enumerated next — a named mana creature may still be tapped for
 * mana on this very cast, which is a real line and not an oversight.
 */
internal fun applyChosenPowerSource(
    state: GameState,
    chosen: ChosenPowerSource,
): AdvanceResult {
    val cast = state.pendingCast ?: error("no cast is gathering decisions")
    require(cast.costPowerSource == null) { "CR 601.2b: this cast's power source is already chosen" }
    return pauseForNextCastDecision(
        state.copy(pendingCast = cast.copy(costPowerSource = persistentListOf(chosen))),
    )
}
