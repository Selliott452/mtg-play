package dev.mtgplay.rules

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import dev.mtgplay.rules.decision.PriorityOption
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList

/*
 * Shared test drivers for the P1.2 engine specs. Auto-responders live here — in test source —
 * by design: the engine itself never auto-passes (architect decision, ADR-004); convenience
 * belongs to drivers.
 */

internal val alice = PlayerId(0)
internal val bob = PlayerId(1)

internal const val DECK_SIZE: Int = 60
internal const val OPENING_HAND_SIZE: Int = 7
internal const val STARTING_LIFE: Int = 20

/** A lands-only deck: [size] copies of Mountain, the packet's acceptance deck. */
internal fun mountainDeck(size: Int = DECK_SIZE): List<CardRef> = List(size) { CardRef("Mountain") }

/** The standard two-player acceptance config: both seats on 60 Mountains, seed-determined. */
internal fun mountainConfig(
    seed: Long = 0x5EED,
    startingPlayer: PlayerId? = alice,
): MatchConfig =
    MatchConfig(
        seed = seed,
        libraries = mapOf(alice to mountainDeck(), bob to mountainDeck()),
        startingPlayer = startingPlayer,
    )

/**
 * The pass-everything, discard-lowest-index auto-responder: passes every priority window and
 * answers discard requests with the lowest stable indices. It never initiates a cast, so the
 * casting requests are unreachable for it — reaching one fails loudly instead of guessing.
 */
internal fun respondTo(request: DecisionRequest): Decision =
    when (request) {
        is DecisionRequest.ChooseAction -> {
            val passIndex = request.options.indexOfFirst { it is PriorityOption.Pass }
            check(passIndex >= 0) { "CR 117.3d: passing must always be enumerated, options were ${request.options}" }
            Decision.SingleSelect(request.id, passIndex)
        }
        is DecisionRequest.ChooseDiscards -> Decision.MultiSelect(request.id, (0 until request.count).toList())
        // CR 508.1 / CR 509.1: this policy attacks and blocks with nothing.
        is DecisionRequest.DeclareAttackers -> Decision.MultiSelect(request.id, emptyList())
        is DecisionRequest.DeclareBlockers -> Decision.MultiSelect(request.id, emptyList())
        is DecisionRequest.ChooseTargets ->
            error("the pass-everything responder never casts, but a targets request surfaced: $request")
        is DecisionRequest.ChoosePaymentPlan ->
            error("the pass-everything responder never casts, but a payment request surfaced: $request")
        is DecisionRequest.OrderBlockers ->
            error("the pass-everything responder never blocks, but a blocker-order request surfaced: $request")
        is DecisionRequest.AssignTrampleDamage ->
            error("the pass-everything responder never attacks, but a trample-assignment request surfaced: $request")
        // CR 603.3b: order any simultaneous triggers in the deterministic identity permutation.
        is DecisionRequest.OrderTriggers -> Decision.MultiSelect(request.id, request.options.indices.toList())
        // CR 702.35b: a passive game may still discard a madness card at cleanup; decline the reflexive cast.
        is DecisionRequest.ChooseYesNo -> Decision.SingleSelect(request.id, DecisionRequest.ChooseYesNo.DECLINE)
        is DecisionRequest.ChooseCardsToExile ->
            error("the pass-everything responder never casts, but an exile-cost request surfaced: $request")
        is DecisionRequest.ChooseReplacement ->
            error("the pass-everything responder never discards two-replacement cards, but one surfaced: $request")
    }

/** One engine suspension observed while driving a game: the paused state and its request. */
internal data class RecordedPause(
    val state: GameState,
    val request: DecisionRequest,
)

/** Everything a driven-to-completion game produced, for assertions and replay. */
internal data class CompletedGame(
    val finalState: GameState,
    val result: MatchResult,
    val decisions: List<Decision>,
    val pauses: List<RecordedPause>,
)

/** Drives a game to completion with [respondTo], recording every pause and decision. */
internal fun playToCompletion(
    engine: GameEngine = DefaultGameEngine(),
    config: MatchConfig = mountainConfig(),
    maxDecisions: Int = 10_000,
): CompletedGame {
    val decisions = mutableListOf<Decision>()
    val pauses = mutableListOf<RecordedPause>()
    var current = engine.start(config)
    while (true) {
        when (val result = current) {
            is AdvanceResult.GameOver ->
                return CompletedGame(result.state, result.result, decisions.toList(), pauses.toList())
            is AdvanceResult.NeedsDecision -> {
                check(decisions.size < maxDecisions) { "game did not terminate within $maxDecisions decisions" }
                pauses += RecordedPause(result.state, result.request)
                val decision = respondTo(result.request)
                decisions += decision
                current = engine.advance(result.state, decision)
            }
        }
    }
}

/** Replays a recorded decision list against a fresh game from [config] (ADR-006). */
internal fun replay(
    engine: GameEngine,
    config: MatchConfig,
    decisions: List<Decision>,
): AdvanceResult {
    var current = engine.start(config)
    for (decision in decisions) {
        val paused = current as? AdvanceResult.NeedsDecision ?: error("decision list is longer than the game")
        current = engine.advance(paused.state, decision)
    }
    return current
}

/** Mountain [GameObject]s with the given ids, for handcrafted states. */
internal fun mountains(
    ids: LongRange,
    owner: PlayerId,
): PersistentList<GameObject> = ids.map { GameObject(ObjectId(it), CardRef("Mountain"), owner) }.toPersistentList()

/** A [PlayerState] with the given zones and defaults for everything else. */
internal fun playerWithZones(
    life: Int = STARTING_LIFE,
    library: PersistentList<GameObject> = persistentListOf(),
    hand: PersistentList<GameObject> = persistentListOf(),
): PlayerState =
    PlayerState(
        life = life,
        library = library,
        hand = hand,
        graveyard = persistentListOf(),
    )

/**
 * A handcrafted two-player [GameState] — a valid engine input by construction (ADR-004) —
 * with empty shared zones and no definitions; casting scenarios use `fixtureState` instead.
 */
internal fun twoPlayerState(
    turn: Turn,
    aliceState: PlayerState,
    bobState: PlayerState,
    nextObjectId: Long,
): GameState =
    GameState(
        players = persistentMapOf(alice to aliceState, bob to bobState),
        turn = turn,
        sharedZones =
            SharedZones(battlefield = persistentListOf(), stack = persistentListOf(), exile = persistentListOf()),
        nextObjectId = nextObjectId,
        rng = Rng(0),
        events = persistentListOf(),
    )

/**
 * The pass decision for [seat]'s current priority window in [state], built from the public
 * request-identity scheme (seat + answered-decision count, see [DecisionRequestId]).
 */
internal fun passDecisionFor(
    state: GameState,
    seat: PlayerId,
): Decision.SingleSelect {
    val ordinal = state.players.getValue(seat).decisionsAnswered
    return Decision.SingleSelect(DecisionRequestId(seat, ordinal), 0)
}
