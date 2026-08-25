package dev.mtgplay.rules.engine

import dev.mtgplay.core.definition.CastSource
import dev.mtgplay.core.definition.CastingPermission
import dev.mtgplay.core.definition.ResolutionEffect
import dev.mtgplay.core.definition.TriggerCondition
import dev.mtgplay.core.definition.TriggerZoneScope
import dev.mtgplay.core.definition.TriggeredAbility
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PendingRebound
import dev.mtgplay.core.state.PendingTrigger
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.rules.AdvanceResult
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import kotlinx.collections.immutable.toPersistentList

/*
 * Rebound (CR 702.88, `FW-BLINK`, docs/design/exile-and-return.md §5) — Ephemerate.
 *
 * CR 702.88a spells the keyword out in full: *"If this spell was cast from your hand, instead of putting
 * it into your graveyard as it resolves, exile it and, at the beginning of your next upkeep, you may cast
 * this card from exile without paying its mana cost."* Three separable halves, and this file is all
 * three:
 *
 * 1. **The leave-stack replacement**, [reboundReplacesGraveyardMove] — narrow in two ways that both
 *    matter. It applies only to a spell cast **from a hand**, so the rebounded cast (which comes from
 *    exile) finishes in the graveyard and the loop terminates with no separate guard; and only **as it
 *    resolves**, so a countered or fizzled Ephemerate does not rebound.
 * 2. **The delayed ability**, [fireReboundTriggers] — checked as each upkeep begins, for cards whose
 *    [dev.mtgplay.core.state.GameObject.reboundTurn] is strictly earlier than the current turn. "Your
 *    *next* upkeep" is what that strictness buys: a spell that resolved during its own controller's
 *    upkeep must not rebound in that same upkeep.
 * 3. **The free cast**, [resolveReboundTrigger] onward — the madness reflexive-cast shape (CR 702.35b),
 *    with the single rules difference that a declined rebound leaves the card in exile rather than
 *    putting it into a graveyard.
 *
 * **This is a narrow rebound, not a delayed-triggered-ability framework.** CR 603.7 — an effect creating
 * a delayed trigger that fires on a future event, in general — remains absent from the engine. What is
 * implemented is the one shape Ephemerate needs, marked on the object rather than held in a general
 * queue, in exactly the idiom `plottedTurn` and `awaitingMadness` already set. §5.1 of the design note
 * records what a general framework would have to add.
 */

/**
 * The synthesized delayed ability a rebounding card's upkeep trigger carries (CR 702.88b). Its effect is
 * never run — the may-cast is the engine's, like madness's (CR 702.35b) — so it is a no-op, and the
 * condition is what [resolveAbility] dispatches on.
 */
private val reboundAbility =
    TriggeredAbility(
        condition = TriggerCondition.ReboundCast,
        effect = ResolutionEffect { state, _ -> state },
        zoneScope = TriggerZoneScope.Exile,
    )

/**
 * Whether rebound replaces [entry]'s CR 608.2m graveyard move as it resolves (CR 702.88a): the card has
 * rebound, and this spell was cast **from a hand**.
 *
 * A spell cast through any [CastingPermission] whose source is not a hand — which for a rebounding card
 * means the rebound cast itself, from exile — is not covered by CR 702.88a's "if this spell was cast from
 * your hand", so it goes to the graveyard. That single condition is what stops Ephemerate rebounding
 * forever, and it is the rule rather than a guard invented for the engine.
 */
internal fun reboundReplacesGraveyardMove(entry: StackEntry.Spell): Boolean {
    if (!entry.definition.rebound) return false
    val source = entry.castVia?.source ?: CastSource.HAND
    return source == CastSource.HAND
}

/**
 * Marks the card [exileObjectId] — a rebounding spell that has just been exiled instead of going to its
 * owner's graveyard — as awaiting its delayed cast on turn [turn] (CR 702.88a).
 *
 * Fails loudly if the card is not in exile: the caller exiled it one step earlier, so a missing one is an
 * engine defect.
 */
internal fun markReboundExile(
    state: GameState,
    exileObjectId: dev.mtgplay.core.identity.ObjectId,
    turn: Int,
): GameState {
    require(state.sharedZones.exile.any { it.id == exileObjectId }) {
        "CR 702.88a: a rebounding card must be in exile to be marked, but $exileObjectId is not"
    }
    return state.updateExile { exile ->
        exile
            .map { if (it.id == exileObjectId) it.copy(reboundTurn = turn) else it }
            .toPersistentList()
    }
}

/**
 * Fires the rebound delayed abilities owed to [player] as their upkeep begins (CR 702.88a): every exile
 * card marked with a [dev.mtgplay.core.state.GameObject.reboundTurn] **strictly earlier** than the
 * current turn, owned by [player], enqueues a [TriggerCondition.ReboundCast] trigger carrying that card
 * as its subject.
 *
 * The strict comparison is "your **next** upkeep": a rebounding spell that resolved during its own
 * controller's upkeep is marked with that turn, and must wait for the following one. A card marked on the
 * opponent's turn is already strictly earlier when this player's next upkeep arrives, so the ordinary
 * case needs no special handling.
 *
 * Control is ownership in the current pool, so the owner of the exiled card is its controller for
 * CR 702.88a's "your next upkeep".
 */
internal fun fireReboundTriggers(
    state: GameState,
    player: PlayerId,
): GameState =
    state.sharedZones.exile
        .filter { it.owner == player && (it.reboundTurn ?: Int.MAX_VALUE) < state.turn.number }
        .fold(state) { current, exiled ->
            enqueuePendingTrigger(
                current,
                PendingTrigger(
                    sourceId = exiled.id,
                    sourceCard = exiled.card,
                    controller = player,
                    ability = reboundAbility,
                    subject = exiled.id,
                ),
            )
        }

/**
 * Resolves a rebound delayed trigger (CR 702.88b): the ability leaves the stack (CR 113.7a), then, if the
 * free cast is currently possible, the engine suspends on a yes/no ([GameState.pendingRebound]);
 * otherwise the card simply stays in exile. Called from [resolveAbility] when the resolving ability's
 * condition is [TriggerCondition.ReboundCast].
 */
internal fun resolveReboundTrigger(
    state: GameState,
    entry: StackEntry.Ability,
): AdvanceResult {
    check(state.sharedZones.stack.lastOrNull() == entry) { "CR 608.1: only the topmost stack object may resolve" }
    val trigger = entry.trigger
    val exiledId =
        trigger.subject ?: error("CR 702.88b: a rebound trigger carries its exiled card as its subject")
    val controller = trigger.controller
    val ceased =
        state
            .updateStack { it.removingAt(it.lastIndex) }
            .emit(GameEvent.TriggeredAbilityResolved(controller, trigger.sourceCard))
    val exiled = ceased.sharedZones.exile.firstOrNull { it.id == exiledId }
    // CR 603.10: the card may have left exile since the trigger fired; then there is nothing to cast.
    if (exiled == null) return grantPriorityRound(ceased)
    val definition = spellDefinitionOf(ceased, exiled.card)
    // CR 601.2c: the free cast is offered only if a legal target exists — a controller with an empty
    // battlefield at their upkeep cannot cast Ephemerate, so the card simply stays exiled. The cost half
    // is vacuous ({0} always has a plan) and timing is deliberately not checked, for madness's reason
    // (CR 702.88b): the cast happens as the delayed ability resolves, not from a priority window.
    return if (targetsAndCostAvailable(ceased, controller, definition, CastingPermission.Rebound, exiledId)) {
        val pending = ceased.copy(pendingRebound = PendingRebound(controller, exiledId))
        AdvanceResult.NeedsDecision(pending, pendingReboundRequest(pending))
    } else {
        // CR 702.88a: a rebounding card that is not cast this way simply remains exiled.
        grantPriorityRound(clearReboundMark(ceased, exiledId))
    }
}

/**
 * The yes/no free cast the open [GameState.pendingRebound] is waiting on (CR 702.88b). A pure function of
 * the state (ADR-004).
 */
internal fun pendingReboundRequest(state: GameState): DecisionRequest.ChooseYesNo {
    val pending = state.pendingRebound ?: error("no rebound cast choice is pending")
    val exiled =
        state.sharedZones.exile.firstOrNull { it.id == pending.exiledObjectId }
            ?: error("CR 702.88b: the pending rebound card ${pending.exiledObjectId} is not in exile")
    return DecisionRequest.ChooseYesNo(
        id = DecisionRequestId(pending.controller, state.player(pending.controller).decisionsAnswered),
        prompt = "cast ${exiled.card.name} from exile without paying its mana cost",
        cardObjectId = pending.exiledObjectId,
        card = exiled.card,
    )
}

/**
 * Applies the controller's rebound yes/no (CR 702.88b): [accept] `true` opens a cast of the exiled card
 * from exile for `{0}` — the normal CR 601 pipeline, the controller holding priority throughout — and
 * [accept] `false` **leaves the card in exile**, which is where rebound and madness part company
 * (CR 702.35b puts a declined madness card into a graveyard; CR 702.88a says nothing, so nothing
 * happens). Either way the rebound mark is cleared, so the card does not offer itself again next upkeep.
 */
internal fun applyReboundCastChoice(
    state: GameState,
    accept: Boolean,
): AdvanceResult {
    val pending = state.pendingRebound ?: error("no rebound cast choice is pending")
    val cleared = state.copy(pendingRebound = null)
    val unmarked = clearReboundMark(cleared, pending.exiledObjectId)
    if (!accept) return grantPriorityRound(unmarked)
    // The controller casts as the delayed ability resolves; they hold priority for the gathering (CR 601.2).
    val casting = unmarked.updatePlayer(pending.controller) { it.copy(priorityStatus = PriorityStatus.HOLDS_PRIORITY) }
    return beginCastGathering(
        casting,
        pending.controller,
        pending.exiledObjectId,
        CastSource.EXILE,
        CastingPermission.Rebound,
    )
}

/**
 * Clears the [dev.mtgplay.core.state.GameObject.reboundTurn] mark on [exileObjectId] (CR 702.88a): the
 * delayed ability has had its one chance, whether it was taken, declined, or impossible. A no-op if the
 * card has already left exile.
 *
 * Clearing rather than leaving the mark is what makes "at the beginning of your **next** upkeep" fire
 * exactly once: a card left marked would offer itself again every upkeep for the rest of the game.
 */
private fun clearReboundMark(
    state: GameState,
    exileObjectId: dev.mtgplay.core.identity.ObjectId,
): GameState =
    state.updateExile { exile ->
        exile
            .map { if (it.id == exileObjectId) it.copy(reboundTurn = null) else it }
            .toPersistentList()
    }
