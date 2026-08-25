package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.AdditionalCost
import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.definition.TargetSpec
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
    // CR 601.2b: a modal card needs its mode before anything else; every other card settles empty.
    val chosenModes: PersistentList<Int>? = if (definition.modes.isEmpty()) persistentListOf() else null
    // CR 601.2c: a modal card's targeting line is unknown until its mode is chosen, so the targets stay
    // unsettled here and are settled by [applyChosenModes] once the spec is known.
    val chosenTargets: PersistentList<Target>? =
        if (chosenModes == null) null else initialTargetsFor(state, definition.targetSpec, caster, cardObjectId)
    // An additional "exile N others" cost (escape) needs a selection; every other cast settles it empty.
    val additionalExileCost: PersistentList<ObjectId>? =
        if ((permission?.additionalExileCount ?: 0) > 0) null else persistentListOf()
    // A non-mana sacrifice cost (Fireblast, Lava Dart) needs a selection; every other cast settles empty.
    val sacrificeCost: PersistentList<ObjectId>? =
        if (permission?.sacrifice != null) null else persistentListOf()
    // An additional discard cost (Grab the Prize) needs a selection; every other cast settles empty.
    val additionalDiscard: PersistentList<ObjectId>? =
        if (definition.additionalCost is AdditionalCost.DiscardCards) null else persistentListOf()
    // An intrinsic sacrifice additional cost (Eviscerator's Insight) needs a selection; every other
    // cast settles empty. It applies to a permission cast too (CR 702.34a's "and any additional
    // costs"), so it is read off the definition rather than off the permission.
    val additionalSacrifice: PersistentList<ObjectId>? =
        if (definition.additionalCost is AdditionalCost.Sacrifice) null else persistentListOf()
    val gathering =
        state.copy(
            pendingCast =
                PendingCast(
                    caster = caster,
                    cardObjectId = cardObjectId,
                    chosenModes = chosenModes,
                    chosenTargets = chosenTargets,
                    source = source,
                    castingPermission = permission,
                    additionalExileCost = additionalExileCost,
                    sacrificeCost = sacrificeCost,
                    additionalDiscard = additionalDiscard,
                    additionalSacrifice = additionalSacrifice,
                ),
        )
    return pauseForNextCastDecision(gathering)
}

/**
 * The settled-targets value a cast starts with for [spec] (CR 601.2c): the empty list for a spell that
 * targets nothing — there is no choice to surface — and `null`, meaning "still to be chosen", for every
 * spec that demands a target.
 *
 * Shared by [beginCastGathering] and [applyChosenModes] because a modal cast reaches the same question
 * twice: once for the card (whose answer is always "unknown", since a modal card has no spec of its
 * own), and again for the mode it settled on.
 */
private fun initialTargetsFor(
    state: GameState,
    spec: TargetSpec,
    caster: PlayerId,
    self: ObjectId,
): PersistentList<Target>? = if (targetChoiceIsVacuous(state, spec, caster, self)) persistentListOf() else null

/**
 * Records the chosen mode on the open [PendingCast] (CR 601.2b, CR 700.2) and suspends for whatever the
 * cast needs next — which for every modal card in the pool is that mode's target choice (CR 601.2c).
 *
 * [modeIndex] is the mode's **printed** index, translated from the option index by the caller, because
 * the printed index is what the cast record and the replay log carry.
 *
 * The targets are settled here too, and only here: a mode that targets nothing settles them empty so no
 * empty `ChooseTargets` is ever surfaced, and a mode that targets leaves them `null` so the next request
 * enumerates against *that mode's* spec. This is the single point at which the CR 601.2b answer
 * determines the CR 601.2c question.
 */
internal fun applyChosenModes(
    state: GameState,
    modeIndex: Int,
): AdvanceResult {
    val cast = state.pendingCast ?: error("no cast is gathering decisions")
    require(cast.chosenModes == null) { "CR 601.2b: this cast's modes are already chosen" }
    val card =
        objectInZone(state, cast.caster, cast.source, cast.cardObjectId)
            ?: error("CR 601.2b: pending cast's card ${cast.cardObjectId} is not in ${cast.caster}'s ${cast.source}")
    val definition = spellDefinitionOf(state, card.card)
    val modes = persistentListOf(modeIndex)
    return pauseForNextCastDecision(
        state.copy(
            pendingCast =
                cast.copy(
                    chosenModes = modes,
                    chosenTargets =
                        initialTargetsFor(
                            state,
                            effectiveTargetSpec(definition, modes),
                            cast.caster,
                            cast.cardObjectId,
                        ),
                ),
        ),
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
    require(cast.chosenTargets == null) { "CR 601.2c: this cast's targets are already chosen" }
    return pauseForNextCastDecision(
        state.copy(pendingCast = cast.copy(chosenTargets = targets.toPersistentList())),
    )
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
