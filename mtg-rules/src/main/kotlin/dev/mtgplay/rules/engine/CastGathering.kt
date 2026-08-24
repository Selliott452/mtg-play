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
    val chosenTargets: PersistentList<Target>? =
        when (definition.targetSpec) {
            TargetSpec.None -> persistentListOf()
            // Every other spec — an Aura (CR 601.2c), any-target, target-player, target-opponent,
            // target-permanent, and a spell on the stack — needs a target choice before payment.
            TargetSpec.AnyTarget,
            TargetSpec.TargetPlayer,
            TargetSpec.TargetOpponent,
            is TargetSpec.TargetPermanent,
            is TargetSpec.Enchantable,
            is TargetSpec.SpellOnStack,
            -> null
        }
    // An additional "exile N others" cost (escape) needs a selection; every other cast settles it empty.
    val additionalExileCost: PersistentList<ObjectId>? =
        if ((permission?.additionalExileCount ?: 0) > 0) null else persistentListOf()
    // A non-mana sacrifice cost (Fireblast, Lava Dart) needs a selection; every other cast settles empty.
    val sacrificeCost: PersistentList<ObjectId>? =
        if (permission?.sacrifice != null) null else persistentListOf()
    // An additional discard cost (Grab the Prize) needs a selection; every other cast settles empty.
    val additionalDiscard: PersistentList<ObjectId>? =
        if (definition.additionalCost is AdditionalCost.DiscardCards) null else persistentListOf()
    val gathering =
        state.copy(
            pendingCast =
                PendingCast(
                    caster,
                    cardObjectId,
                    chosenTargets,
                    source,
                    permission,
                    additionalExileCost,
                    sacrificeCost,
                    additionalDiscard,
                ),
        )
    return pauseForNextCastDecision(gathering)
}

/** Suspends for whatever the open [PendingCast] still needs (ADR-004). */
internal fun pauseForNextCastDecision(state: GameState): AdvanceResult {
    val cast = state.pendingCast ?: error("no cast is gathering decisions")
    return AdvanceResult.NeedsDecision(state, pendingCastRequest(state, cast))
}

/**
 * Records the chosen target on the open [PendingCast] (CR 601.2c) and suspends for the payment
 * choice.
 */
internal fun applyChosenTarget(
    state: GameState,
    target: Target,
): AdvanceResult {
    val cast = state.pendingCast ?: error("no cast is gathering decisions")
    require(cast.chosenTargets == null) { "CR 601.2c: this cast's targets are already chosen" }
    return pauseForNextCastDecision(
        state.copy(pendingCast = cast.copy(chosenTargets = persistentListOf(target))),
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
