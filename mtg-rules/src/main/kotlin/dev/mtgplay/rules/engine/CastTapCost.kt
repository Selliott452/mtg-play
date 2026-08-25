package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.TapRequirement
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingCast
import dev.mtgplay.rules.AdvanceResult
import kotlinx.collections.immutable.toPersistentList

/*
 * The **tap** component of a casting permission's non-mana cost (CR 601.2h, CR 702.34c) — Prismatic
 * Strands' "Flashback—Tap an untapped white creature you control". Additive (`FW-PREVENT2`).
 *
 * Its own file rather than a few more functions on CastLegality.kt and CastGathering.kt, which the
 * function-per-file budget would not take — the same forcing function that split `CastCostPayment.kt`
 * out of `CastingPipeline.kt` and `abilityCostSelectionToDto` out of its parent. Keeping the cost's
 * three halves together (which permanents can pay it, whether enough of them exist, and recording the
 * answer) is the better arrangement anyway: they must agree with each other, and they now disagree in
 * one place if they disagree at all.
 *
 * The two stages this file does *not* own are the request itself (`PendingCastRequest.kt`, where every
 * cast stage's request is derived in CR 601.2 order) and the payment (`CastCostPayment.kt`, beside the
 * sibling sacrifice and discard stages).
 */

/**
 * The battlefield permanents [seat] controls that can pay [requirement] (CR 601.2h): their own
 * **untapped** permanents of the requirement's card type and colour, in battlefield order. Control is
 * ownership in the MVP pool.
 *
 * Colour is read from [dev.mtgplay.core.card.PrintedCharacteristics.colors], the same derivation
 * CR 702.16e protection and the CR 615.1 colour shield use, so a
 * [dev.mtgplay.core.card.Keyword.DEVOID] permanent is colourless and never offered (CR 702.114a). Card
 * type is read printed, like every other card-type read in the engine.
 *
 * **Untapped is the only status consulted.** Summoning sickness is not: CR 302.6 restricts the `{T}`
 * symbol in an activated ability *of that permanent*, and this is a cost of a spell (see
 * [dev.mtgplay.core.definition.TapRequirement]). A creature that arrived this turn is offered, which is
 * the ruling and a real line of play.
 */
internal fun tappableFor(
    state: GameState,
    seat: PlayerId,
    requirement: TapRequirement,
): List<GameObject> =
    state.sharedZones.battlefield.filter { obj ->
        val printed = state.definitions[obj.card]?.characteristics
        obj.owner == seat &&
            !obj.tapped &&
            printed != null &&
            requirement.cardType in printed.cardTypes &&
            requirement.color in printed.colors
    }

/**
 * Whether a non-mana [requirement] tap cost can be paid: [seat] controls at least the required count of
 * matching untapped permanents. Trivially true when the permission has no tap cost (`null`).
 */
internal fun tapSatisfiable(
    state: GameState,
    seat: PlayerId,
    requirement: TapRequirement?,
): Boolean = requirement == null || tappableFor(state, seat, requirement).size >= requirement.count

/**
 * Records the permanents chosen to pay a non-mana **tap** cost (Prismatic Strands' flashback —
 * CR 601.2h, CR 702.34c) on the open [PendingCast] and suspends for the payment choice. They are tapped
 * only when the cast executes (CR 601.2h), atomically with everything else — so a creature answered
 * here is still untapped while the sibling cost selections and the payment plan are enumerated, which
 * is what makes a summoning-sick creature answerable (CR 302.6 restricts abilities *of* a permanent,
 * and this is a spell's cost).
 */
internal fun applyChosenTapCost(
    state: GameState,
    tapObjectIds: List<ObjectId>,
): AdvanceResult {
    val cast = state.pendingCast ?: error("no cast is gathering decisions")
    require(cast.tapCost == null) { "CR 601.2h: this cast's tap cost is already chosen" }
    return pauseForNextCastDecision(
        state.copy(pendingCast = cast.copy(tapCost = tapObjectIds.toPersistentList())),
    )
}
