package dev.mtgplay.rules

import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.PriorityOption
import dev.mtgplay.rules.engine.pendingDecisionRequest
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/*
 * Handcrafted-state builders and decision helpers for the P2.1 casting specs. States are valid
 * engine inputs by construction (ADR-004): the named priority holder is mid-window, so the
 * engine re-derives the pending request from the state alone.
 */

/**
 * A handcrafted two-player state with the fixture-definition registry, [holder] mid-priority
 * window (CR 117.1), and ids allocated sequentially. Battlefield objects are untapped.
 *
 * [definitions] defaults to [fixtureDefinitions]; a spec with its own fixtures passes the merged
 * registry (the shape [CastFromElsewhereSpec] and the ramp-Aura specs already use).
 */
internal fun fixtureState(
    aliceSetup: SeatSetup,
    bobSetup: SeatSetup,
    turn: Turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
    holder: PlayerId = alice,
    definitions: Map<CardRef, CardDefinition> = fixtureDefinitions,
): GameState {
    var nextId = 0L

    fun objects(
        names: List<String>,
        owner: PlayerId,
    ) = names.map { GameObject(ObjectId(nextId), CardRef(it), owner).also { _ -> nextId += 1 } }.toPersistentList()

    fun seat(
        setup: SeatSetup,
        owner: PlayerId,
    ): Pair<PlayerState, List<GameObject>> {
        val battlefield = objects(setup.battlefield, owner)
        val player =
            PlayerState(
                life = setup.life,
                library = objects(setup.library, owner),
                hand = objects(setup.hand, owner),
                graveyard = persistentListOf(),
                priorityStatus = if (owner == holder) PriorityStatus.HOLDS_PRIORITY else PriorityStatus.NONE,
            )
        return player to battlefield
    }

    val (alicePlayer, aliceField) = seat(aliceSetup, alice)
    val (bobPlayer, bobField) = seat(bobSetup, bob)
    return GameState(
        players = persistentMapOf(alice to alicePlayer, bob to bobPlayer),
        turn = turn,
        sharedZones =
            SharedZones(
                battlefield = (aliceField + bobField).toPersistentList(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = definitions.toPersistentMap(),
    )
}

/** The pending request of a paused result, with its kind checked. */
internal inline fun <reified R : DecisionRequest> AdvanceResult.pending(): R =
    shouldBeInstanceOf<AdvanceResult.NeedsDecision>().request.shouldBeInstanceOf<R>()

/** The request a handcrafted paused [state] is waiting on, with its kind checked (ADR-004). */
internal inline fun <reified R : DecisionRequest> pausedRequestOf(state: GameState): R =
    (pendingDecisionRequest(state) ?: error("state is not paused at a decision point")).shouldBeInstanceOf<R>()

/** The paused state of a result; fails if the game is over. */
internal val AdvanceResult.pausedState: GameState
    get() = shouldBeInstanceOf<AdvanceResult.NeedsDecision>().state

/** Selects the [PriorityOption.CastSpell] of the named [card] from a priority window. */
internal fun castDecision(
    request: DecisionRequest.ChooseAction,
    card: String,
): Decision.SingleSelect {
    val index =
        request.options.indexOfFirst { it is PriorityOption.CastSpell && it.card == CardRef(card) }
    check(index >= 0) { "no CastSpell option for $card in ${request.options}" }
    return Decision.SingleSelect(request.id, index)
}

/** Selects the [PriorityOption.PlayLand] of the named [card] from a priority window (CR 116.2a). */
internal fun playLandDecision(
    request: DecisionRequest.ChooseAction,
    card: String,
): Decision.SingleSelect {
    val index =
        request.options.indexOfFirst { it is PriorityOption.PlayLand && it.card == CardRef(card) }
    check(index >= 0) { "no PlayLand option for $card in ${request.options}" }
    return Decision.SingleSelect(request.id, index)
}

/** Whether [request] enumerates any play-land option (CR 116.2a). */
internal fun hasPlayLand(request: DecisionRequest.ChooseAction): Boolean =
    request.options.any { it is PriorityOption.PlayLand }

/** Selects the pass option from a priority window (CR 117.3d). */
internal fun passDecision(request: DecisionRequest.ChooseAction): Decision.SingleSelect {
    val index = request.options.indexOfFirst { it is PriorityOption.Pass }
    check(index >= 0) { "CR 117.3d: passing must always be enumerated" }
    return Decision.SingleSelect(request.id, index)
}

/** Selects the targeted player [seat] from a targets request (CR 601.2c). */
internal fun targetDecision(
    request: DecisionRequest.ChooseTargets,
    seat: PlayerId,
): Decision.SingleSelect {
    val index = request.options.indexOfFirst { it == Target.Player(seat) }
    check(index >= 0) { "no player target for $seat in ${request.options}" }
    return Decision.SingleSelect(request.id, index)
}

/** Selects the payment plan at [index] (0 by default) from a payment request (CR 601.2g). */
internal fun planDecision(
    request: DecisionRequest.ChoosePaymentPlan,
    index: Int = 0,
): Decision.SingleSelect = Decision.SingleSelect(request.id, index)

/** The names of the cards whose casts are enumerated in [request], in option order. */
internal fun enumeratedCasts(request: DecisionRequest.ChooseAction): List<String> =
    request.options.filterIsInstance<PriorityOption.CastSpell>().map { it.card.name }
