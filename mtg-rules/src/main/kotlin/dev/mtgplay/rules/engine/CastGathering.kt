package dev.mtgplay.rules.engine

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

/*
 * The gathering half of the CR 601 casting pipeline (ADR-004): choosing to cast opens a
 * [PendingCast] on the state, and the engine suspends once per choice the cast needs —
 * targets (CR 601.2c), then a payment plan (CR 601.2g) — each re-derivable from the paused
 * state alone. While gathering, the card is still in the caster's hand and nothing about the
 * game has changed; the execution half (`CastingPipeline.kt`) runs atomically once the last
 * choice arrives.
 */

/**
 * The caster chose [dev.mtgplay.rules.decision.PriorityOption.CastSpell] (CR 601.2): opens the
 * [PendingCast] and suspends for the cast's first choice. A spell that targets nothing skips
 * straight to the payment choice with its target list settled empty.
 */
internal fun beginCastGathering(
    state: GameState,
    caster: PlayerId,
    cardObjectId: ObjectId,
): AdvanceResult {
    val card =
        state.player(caster).hand.firstOrNull { it.id == cardObjectId }
            ?: error("CR 601.2: object $cardObjectId is not in $caster's hand")
    val definition = spellDefinitionOf(state, card.card)
    val chosenTargets: PersistentList<Target>? =
        when (definition.targetSpec) {
            TargetSpec.None -> persistentListOf()
            // An Aura (CR 601.2c) and any-target both need a target choice before payment.
            TargetSpec.AnyTarget, is TargetSpec.Enchantable -> null
        }
    val gathering = state.copy(pendingCast = PendingCast(caster, cardObjectId, chosenTargets))
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
