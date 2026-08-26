package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.AdditionalCost
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.SacrificeRequirement
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.ChosenPowerSource
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState

/*
 * Cast legality shared by enumeration (ADR-005) and the madness reflexive-cast viability check. Every
 * gate here excludes a cast that would dead-end mid-pipeline, so a surfaced cast always completes.
 */

/**
 * Whether every target [definition] requires is available and its cost via [permission] can be paid
 * (CR 601.2c, g). [self] is the card that would be cast, excluded from its own target enumeration
 * **and** from every zone count the cost reduction takes (CR 601.2a).
 *
 * The cost comes from the shared [totalCost] (CR 601.2f, docs/design/cost-modification.md) rather than
 * from a caller-supplied `ManaCost`. Taking the permission instead of an already-priced cost is what
 * makes divergence unrepresentable here: a caller can no longer hand this the printed cost while the
 * pipeline pays a reduced one.
 *
 * The card's intrinsic **sacrifice** additional cost is part of "can be paid" here rather than a
 * separate gate at each call site, because it is one of the two things (with the payment plan) that
 * constrain each other: [minimalSacrificeReservation] is what keeps the mana enumeration from offering
 * a plan that spends the very permanent the cost is about to consume. A permission cast pays the card's
 * additional costs too — CR 702.34a's "and any additional costs" — so flashing back Eviscerator's
 * Insight is gated on the sacrifice as well, through this same call.
 */
internal fun targetsAndCostAvailable(
    state: GameState,
    seat: PlayerId,
    definition: SpellDefinition,
    permission: CastingPermission?,
    self: ObjectId,
): Boolean =
    // CR 601.2b–c: "its targets are available" means *some mode's* targets for a modal card (`FW-MODAL`).
    // The card is always a real object here — every caller names one — so it is a [Chooser.Spell] and
    // CR 702.16b has a source to test (CR 113.7c: a spell is its own source).
    someModeIsCastable(state, definition, seat, Chooser.Spell(self)) &&
        additionalSacrificeSatisfiable(state, seat, definition) &&
        enumeratePaymentPlans(
            state,
            seat,
            // CR 601.2b: priced at the cheapest announcement — no kicker, X = 0 — because that is what
            // "is this castable at all?" means: declining a kicker is always legal and a larger X only
            // ever costs more, so a cast payable at any announcement is payable at this one.
            // CR 601.2c/f: and at the cheapest *target choice*, for the same reason in the same direction
            // — a target-conditional reduction (Ride's End) applies as soon as some legal choice would
            // make it apply, so pricing the printed cost here would hide a payable cast (`FW-TGTCOND`).
            totalCost(
                state,
                seat,
                CastSubject(definition, permission, self, cheapestTargetsFor(state, seat, definition, self)),
            ),
            minimalSacrificeReservation(state, seat, definition),
        ).isNotEmpty()

/**
 * Whether [seat] may cast the card [sourceObject] via [permission] from a priority window (CR 117.1a):
 * the card's timing permits it, the permission's own state condition holds, the additional "exile N
 * others" cost is satisfiable, and its targets and alternative cost are available. Used to enumerate
 * flashback, escape, and hand alternative-cost casts (ADR-005).
 */
internal fun permissionCastIsLegal(
    state: GameState,
    seat: PlayerId,
    definition: SpellDefinition,
    permission: CastingPermission,
    sourceObject: GameObject,
): Boolean =
    timingPermitsWindow(state, seat, definition.timing) &&
        castConditionHolds(state, seat, permission) &&
        additionalExileSatisfiable(state, seat, permission, sourceObject) &&
        sacrificeSatisfiable(state, seat, permission.sacrifice) &&
        tapSatisfiable(state, seat, permission.tap) &&
        additionalDiscardSatisfiable(state, seat, definition, sourceObject.id, permission.source) &&
        // CR 702.34a's "and any additional costs" applies to a permission cast too (`W9-D`).
        powerSourceCostSatisfiable(state, seat, definition, sourceObject.id, permission.source) &&
        plotMarkerAllows(state, permission, sourceObject) &&
        targetsAndCostAvailable(state, seat, definition, permission, self = sourceObject.id)

/**
 * Whether a [CastingPermission.Plot] free cast is allowed for [sourceObject] right now (CR 702.140): the
 * exile card must have been plotted and not this turn ([plotFreeCastLegal]). Trivially true for every
 * other permission — only plot gates on the plotted-turn marker.
 */
private fun plotMarkerAllows(
    state: GameState,
    permission: CastingPermission,
    sourceObject: GameObject,
): Boolean = permission !is CastingPermission.Plot || plotFreeCastLegal(state, sourceObject)

/**
 * Whether a card's intrinsic additional discard cost (Grab the Prize's "discard a card", CR 601.2b)
 * can be paid: the caster has at least the required count of hand cards other than the one being cast.
 * The card being cast is excluded only when it is cast from the hand (source [CastSource.HAND]) — a
 * permission cast draws the card from elsewhere, so the whole hand is discardable. Trivially true when
 * the definition has no additional discard cost.
 */
internal fun additionalDiscardSatisfiable(
    state: GameState,
    seat: PlayerId,
    definition: SpellDefinition,
    castObjectId: ObjectId,
    source: CastSource,
): Boolean {
    val cost = definition.additionalCost
    if (cost !is AdditionalCost.DiscardCards) return true
    val available = state.player(seat).hand.count { !(source == CastSource.HAND && it.id == castObjectId) }
    return available >= cost.count
}

/**
 * Whether a card's **intrinsic** sacrifice additional cost (Eviscerator's Insight's "sacrifice an
 * artifact or creature", Raze's "sacrifice a land" — CR 601.2b) can be paid: the caster controls at
 * least the required count of matching permanents. Trivially true when the definition has no such cost.
 *
 * Unlike the discard cost next door there is nothing to exclude: the card being cast is in the hand or
 * the graveyard, never on the battlefield.
 */
internal fun additionalSacrificeSatisfiable(
    state: GameState,
    seat: PlayerId,
    definition: SpellDefinition,
): Boolean {
    val cost = definition.additionalCost
    if (cost !is AdditionalCost.Sacrifice) return true
    return sacrificeableMatching(state, seat, cost.filter).size >= cost.count
}

/**
 * The things that may be named to pay a card's **non-consuming** additional cost (Monstrous Emergence's
 * "choose a creature you control or reveal a creature card from your hand" — CR 601.2b), in the order the
 * request offers them: the caster's battlefield creatures first, then the creature cards in their hand.
 *
 * The card being cast is excluded from the hand half and only from it — it is in the hand while the cast is
 * gathering (CR 601.2a), and CR 601.2b's "a creature card from your hand" cannot mean the spell itself,
 * which is not a card in hand at all any more. Nothing needs excluding from the battlefield half, where the
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
    val player = state.player(seat)
    val controlled =
        state.sharedZones.battlefield
            .filter { it.owner == seat && isCreature(state, it) }
            .map { PowerSourceOption(ChosenPowerSource.ChosenCreature(it.id), it.card, effectivePower(state, it.id)) }
    val revealable =
        player.hand
            .filter { !(source == CastSource.HAND && it.id == castObjectId) }
            .mapNotNull { card ->
                val printed = state.definitions[card.card]?.characteristics ?: return@mapNotNull null
                if (CardType.CREATURE !in printed.cardTypes) return@mapNotNull null
                // CR 109.3: a card outside the battlefield has its printed characteristics and no others,
                // so a creature card with no printed power is a contradiction the definition registry
                // would have to have produced; there is none, and `0` is not silently substituted.
                val power =
                    printed.powerToughness?.power
                        ?: error("CR 208.1: creature card ${card.card.name} has no printed power")
                PowerSourceOption(ChosenPowerSource.RevealedCard(card.card), card.card, power)
            }
    return controlled + revealable
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
 * a resource (which is why it has no counterpart to [minimalSacrificeReservation]).
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
 * The mana sources a cast's sacrifice additional cost forces out of its own payment plans — the
 * enumeration-time counterpart of the exact, choice-aware reservation
 * [dev.mtgplay.rules.engine.pendingCastRequest] applies once the selection is answered
 * (docs/design/mana-payment.md §2.2).
 *
 * Legality runs *before* the caster has chosen which permanents to sacrifice, so it must answer "is
 * there **some** choice that leaves the cost payable". It reserves the **minimal** set any choice could
 * force: candidates that are not sacrifice-cost mana sources are preferred, so the reservation is empty
 * whenever enough of them exist, and only a board whose every matching permanent produces mana *by*
 * being sacrificed reserves anything at all.
 *
 * Minimal rather than blunt, because over-reserving here would drop a castable spell out of the
 * enumerated action space entirely — a silently missing legal play, which is worse than the crash the
 * reservation exists to prevent. The greedy prefix is exact for a one-permanent cost, which is every
 * such cost the pool prints; for a larger count on a board of nothing but sacrifice-cost mana sources
 * it can be optimistic, and the cost payment then fails **loudly** in [sacrificePermanents] rather than
 * producing a wrong game state.
 */
internal fun minimalSacrificeReservation(
    state: GameState,
    seat: PlayerId,
    definition: SpellDefinition,
): Set<ObjectId> {
    val cost = definition.additionalCost
    if (cost !is AdditionalCost.Sacrifice) return emptySet()
    val cheapestFirst =
        sacrificeableMatching(state, seat, cost.filter)
            .sortedBy { isSacrificeSource(state, it.id) }
            .take(cost.count)
    return sacrificeSourcesAmong(state, cheapestFirst.map { it.id })
}

/**
 * The battlefield permanents [seat] controls that satisfy [requirement] (CR 601.2h): its own
 * permanents matching the requirement's filter — Fireblast's Mountains, Dread Return's creatures — in
 * battlefield order. Control is ownership in the MVP pool.
 *
 * A thin alias for [sacrificeableMatching] since `W8-D` gave [SacrificeRequirement] a
 * [dev.mtgplay.core.definition.SacrificeFilter]. It is kept as its own name rather than inlined at the
 * two call sites because the *requirement* is what a casting permission carries, and reading a
 * permission's option set through a function that takes a bare filter would lose the count that travels
 * with it (see [sacrificeSatisfiable]).
 */
internal fun sacrificeableFor(
    state: GameState,
    seat: PlayerId,
    requirement: SacrificeRequirement,
): List<GameObject> = sacrificeableMatching(state, seat, requirement.filter)

/**
 * Whether a non-mana [requirement] sacrifice cost can be paid: [seat] controls at least the required
 * count of matching permanents. Trivially true when the permission has no sacrifice cost (`null`).
 */
internal fun sacrificeSatisfiable(
    state: GameState,
    seat: PlayerId,
    requirement: SacrificeRequirement?,
): Boolean = requirement == null || sacrificeableFor(state, seat, requirement).size >= requirement.count

/**
 * Whether [permission]'s additional "exile N other cards" cost (CR 702.139a) can be paid: the source
 * zone holds at least that many cards *other* than [sourceObject]. Trivially true when the permission
 * has no such cost.
 */
internal fun additionalExileSatisfiable(
    state: GameState,
    seat: PlayerId,
    permission: CastingPermission,
    sourceObject: GameObject,
): Boolean {
    val needed = permission.additionalExileCount
    if (needed == 0) return true
    val others = objectsInZone(state, seat, permission.source).count { it.id != sourceObject.id }
    return others >= needed
}

/**
 * Whether a madness cast of [permission] is currently possible for [owner] (CR 702.35b): its target
 * and its madness cost are available. Timing is deliberately not checked — a madness card is cast as
 * the reflexive trigger resolves, not from a priority window, so its normal timing does not restrict it
 * (CR 702.35b) — and madness carries no additional cost.
 */
internal fun madnessCastViable(
    state: GameState,
    owner: PlayerId,
    definition: SpellDefinition,
    permission: CastingPermission,
    exiledObjectId: ObjectId,
): Boolean = targetsAndCostAvailable(state, owner, definition, permission, self = exiledObjectId)
