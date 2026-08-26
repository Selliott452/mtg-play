package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.AdditionalCost
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingCast
import dev.mtgplay.core.state.Target
import dev.mtgplay.rules.AdvanceResult
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/*
 * The gathering half of the CR 601 casting pipeline (ADR-004): choosing to cast opens a
 * [PendingCast] on the state, and the engine suspends once per choice the cast needs —
 * targets (CR 601.2c), then a payment plan (CR 601.2g) — each re-derivable from the paused
 * state alone. While gathering, the card is still in the caster's hand and nothing about the
 * game has changed; the execution half (`CastingPipeline.kt`) runs atomically once the last
 * choice arrives.
 */

/**
 * The caster chose to cast [cardObjectId] from [source] via [permission] (CR 601.2): opens the
 * [PendingCast] and suspends for the cast's first choice. A spell that targets nothing skips straight
 * past the target choice; a permission with no additional exile cost settles that list empty. The
 * defaults describe a normal cast from the hand at the printed cost.
 */
internal fun beginCastGathering(
    state: GameState,
    caster: PlayerId,
    cardObjectId: ObjectId,
    source: CastSource = CastSource.HAND,
    permission: CastingPermission? = null,
): AdvanceResult {
    val card =
        objectInZone(state, caster, source, cardObjectId)
            ?: error("CR 601.2: object $cardObjectId is not in $caster's $source zone")
    val definition = spellDefinitionOf(state, card.card)
    // A spell that targets nothing, and an "up to N" spell with nothing legal to point at, settle
    // straight to an empty list; every other spec needs a target choice before payment (CR 601.2c).
    // The `when` this replaced asked seven members the same question; `targetChoiceIsVacuous` asks it
    // once, in the file that owns target legality, so the cast, activation, and trigger paths cannot
    // disagree about when a target choice exists (`FW-MULTITGT`).
    // CR 601.2b: a modal card needs its modes before anything else; every other card settles empty.
    // A modal card with *no* choosable mode settles empty too, and only an "up to N" card can reach
    // that (`someModeIsCastable` refuses the cast otherwise): choosing none is its legal answer, and a
    // request over an empty option list is not a decision.
    val chosenModes: PersistentList<Int>? = initialChosenModes(state, definition, caster, cardObjectId)
    // CR 601.2c: a modal card's targeting lines are unknown until its modes are chosen, so the targets
    // stay unsettled here and are settled by [applyChosenModes] once the specs are known.
    // An additional "exile N others" cost (escape) needs a selection; every other cast settles it empty.
    val additionalExileCost: PersistentList<ObjectId>? =
        if ((permission?.additionalExileCount ?: 0) > 0) null else persistentListOf()
    // A non-mana sacrifice cost (Fireblast, Lava Dart) needs a selection; every other cast settles empty.
    val sacrificeCost: PersistentList<ObjectId>? =
        if (permission?.sacrifice != null) null else persistentListOf()
    // A non-mana tap cost (Prismatic Strands' flashback) needs a selection; every other cast settles
    // empty. Its own field beside `sacrificeCost` because a permission may in principle carry both, and
    // because the two consume their permanents in opposite ways (`FW-PREVENT2`).
    val tapCost: PersistentList<ObjectId>? =
        if (permission != null && permission.tap != null) null else persistentListOf()
    // An additional discard cost (Grab the Prize) needs a selection; every other cast settles empty.
    val additionalDiscard: PersistentList<ObjectId>? =
        if (definition.additionalCost is AdditionalCost.DiscardCards) null else persistentListOf()
    // An intrinsic sacrifice additional cost (Eviscerator's Insight) needs a selection; every other
    // cast settles empty. It applies to a permission cast too (CR 702.34a's "and any additional
    // costs"), so it is read off the definition rather than off the permission.
    val additionalSacrifice: PersistentList<ObjectId>? =
        if (definition.additionalCost is AdditionalCost.Sacrifice) null else persistentListOf()
    // CR 601.2b/702.33a: a kicker announcement is due only for a card printing the keyword *and* only
    // when the kicked cost is affordable — a seat that cannot pay it has nothing to announce, and
    // offering a yes/no whose "yes" dead-ends is the ADR-005 defect. Every other cast settles `false`.
    // CR 601.2b/702.166a: an optional additional cost with a chosen object (bargain) is announced only
    // when the board can actually pay it; a declined or absent one settles both its stages at once.
    val optionalCostTaken: Boolean? = initialOptionalCostAnnouncement(state, caster, definition)
    val optionalCostObjects: PersistentList<ObjectId>? =
        if (optionalCostTaken == null) null else persistentListOf()
    val subject = CastSubject(definition, permission, cardObjectId)
    val kicked: Boolean? =
        if (kickerAffordable(state, caster, subject, minimalSacrificeReservation(state, caster, definition))) {
            null
        } else {
            false
        }
    // CR 107.3b/601.2b: a value of X is announced only when the cost the cast starts from carries the
    // variable; every other cast settles zero. The option set is derived when the request is surfaced,
    // by which point the sibling cost selections that reserve mana sources are settled (`FW-X`).
    val chosenX: Int? = if (announcesX(subject)) null else 0
    // A non-consuming additional cost (Monstrous Emergence) needs something named; every other cast
    // settles empty. Like the sacrifice above it is read off the definition rather than the permission,
    // because CR 702.34a's "and any additional costs" applies to a permission cast too.
    val costPowerSource = initialPowerSourceSettlement(definition)
    val opened =
        PendingCast(
            caster = caster,
            cardObjectId = cardObjectId,
            chosenModes = chosenModes,
            chosenTargets = null,
            source = source,
            castingPermission = permission,
            additionalExileCost = additionalExileCost,
            sacrificeCost = sacrificeCost,
            tapCost = tapCost,
            additionalDiscard = additionalDiscard,
            additionalSacrifice = additionalSacrifice,
            costPowerSource = costPowerSource,
            kicked = kicked,
            optionalCostTaken = optionalCostTaken,
            optionalCostObjects = optionalCostObjects,
            chosenX = chosenX,
        )
    // CR 601.2c: settle every targeting line that has nothing to choose from, which for a non-modal
    // card is the one question it has. A card whose modes are still unchosen has no lines yet.
    return pauseForNextCastDecision(
        state.copy(pendingCast = advanceTargetingLines(state, opened, definition)),
    )
}

/** Suspends for whatever the open [PendingCast] still needs (ADR-004). */
internal fun pauseForNextCastDecision(state: GameState): AdvanceResult {
    val cast = state.pendingCast ?: error("no cast is gathering decisions")
    return AdvanceResult.NeedsDecision(state, pendingCastRequest(state, cast))
}

/**
 * Records the chosen [targets] on the open [PendingCast] (CR 601.2c) and suspends for the payment
 * choice. A list rather than a single value since `FW-MULTITGT`: "up to two target cards from
 * graveyards" settles here with nought, one, or two, and [PendingCast.chosenTargets] was already a list
 * because a `null` — "not yet chosen" — has to be distinguishable from a deliberate empty choice.
 */
internal fun applyChosenTarget(
    state: GameState,
    targets: List<Target>,
): AdvanceResult {
    val cast = state.pendingCast ?: error("no cast is gathering decisions")
    val card =
        objectInZone(state, cast.caster, cast.source, cast.cardObjectId)
            ?: error("CR 601.2c: pending cast's card ${cast.cardObjectId} is not in ${cast.caster}'s ${cast.source}")
    val definition = spellDefinitionOf(state, card.card)
    // The answer belongs to the line the cursor is on, so it is *appended* rather than assigned. The two
    // shapes append to different places, which is the only way they differ: a modal card keeps one list
    // per chosen mode (`W9-B`, "choose up to two"), while an ordinary card printing the word "target"
    // more than once appends to a single flat list that is later sliced back into lines by their fixed
    // widths (`W9-C`, Searing Blaze).
    val answered =
        if (definition.modes.isEmpty()) {
            require(!castTargetLinesSettled(definition, cast)) {
                "CR 601.2c: this cast's targets are already chosen"
            }
            cast.copy(
                chosenTargets =
                    cast.chosenTargets
                        .orEmpty()
                        .toPersistentList()
                        .addingAll(targets),
            )
        } else {
            require(cast.modeTargets.size < effectiveTargetSpecs(definition, cast.chosenModes.orEmpty()).size) {
                "CR 601.2c: this cast's targets are already chosen"
            }
            cast.copy(modeTargets = cast.modeTargets.adding(targets.toPersistentList()))
        }
    return pauseForNextCastDecision(state.copy(pendingCast = advanceTargetingLines(state, answered, definition)))
}

/**
 * Records the cards chosen to pay an additional "exile N other cards" cost (escape, CR 702.139a) on the
 * open [PendingCast] and suspends for the payment choice. The cards are exiled only when the cast
 * executes (CR 601.2h), atomically with everything else.
 */
internal fun applyChosenExileCost(
    state: GameState,
    exileObjectIds: List<ObjectId>,
): AdvanceResult {
    val cast = state.pendingCast ?: error("no cast is gathering decisions")
    require(cast.additionalExileCost == null) { "CR 601.2b: this cast's additional exile cost is already chosen" }
    return pauseForNextCastDecision(
        state.copy(pendingCast = cast.copy(additionalExileCost = exileObjectIds.toPersistentList())),
    )
}

/**
 * Records the permanents chosen to pay a non-mana sacrifice cost (Fireblast, Lava Dart — CR 601.2h) on
 * the open [PendingCast] and suspends for the payment choice. The permanents are sacrificed only when
 * the cast executes (CR 601.2h), atomically with everything else.
 */
internal fun applyChosenSacrifices(
    state: GameState,
    sacrificeObjectIds: List<ObjectId>,
): AdvanceResult {
    val cast = state.pendingCast ?: error("no cast is gathering decisions")
    require(cast.sacrificeCost == null) { "CR 601.2h: this cast's sacrifice cost is already chosen" }
    return pauseForNextCastDecision(
        state.copy(pendingCast = cast.copy(sacrificeCost = sacrificeObjectIds.toPersistentList())),
    )
}

/**
 * Records the hand cards chosen to pay an additional discard cost (Grab the Prize — CR 601.2b) on the
 * open [PendingCast] and suspends for the payment choice. The cards are discarded only when the cast
 * executes (CR 601.2h), through the CR 614/616 framework so madness intercepts them.
 */
internal fun applyChosenAdditionalDiscard(
    state: GameState,
    discardObjectIds: List<ObjectId>,
): AdvanceResult {
    val cast = state.pendingCast ?: error("no cast is gathering decisions")
    require(cast.additionalDiscard == null) { "CR 601.2b: this cast's additional discard cost is already chosen" }
    return pauseForNextCastDecision(
        state.copy(pendingCast = cast.copy(additionalDiscard = discardObjectIds.toPersistentList())),
    )
}

/**
 * Records the permanents chosen to pay an intrinsic sacrifice additional cost (Eviscerator's Insight —
 * CR 601.2b) on the open [PendingCast] and suspends for the payment choice. They are sacrificed only
 * when the cast executes, and **after** the mana payment (CR 601.2g precedes CR 601.2h), so a land
 * answered here is still available to the payment plan that is enumerated next.
 */
internal fun applyChosenAdditionalSacrifice(
    state: GameState,
    sacrificeObjectIds: List<ObjectId>,
): AdvanceResult {
    val cast = state.pendingCast ?: error("no cast is gathering decisions")
    require(cast.additionalSacrifice == null) { "CR 601.2b: this cast's additional sacrifice cost is already chosen" }
    return pauseForNextCastDecision(
        state.copy(pendingCast = cast.copy(additionalSacrifice = sacrificeObjectIds.toPersistentList())),
    )
}

/**
 * The [SpellDefinition] of [card], required because the engine is about to cast or resolve it;
 * fails loudly if the card has no definition or an uncastable one (architect decision, P2.1:
 * inert cards are excluded from enumeration, so reaching this without one is an engine defect).
 */
internal fun spellDefinitionOf(
    state: GameState,
    card: CardRef,
): SpellDefinition =
    state.definitions[card] as? SpellDefinition
        ?: error("card ${card.name} has no castable definition; enumeration must not have offered it (ADR-005)")
