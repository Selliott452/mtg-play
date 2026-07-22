package dev.mtgplay.rules

import dev.mtgplay.core.card.CardType
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.card.PrintedCharacteristics
import dev.mtgplay.core.card.PrintedPowerToughness
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
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.core.state.TurnStep
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/*
 * Handcrafted-battlefield support for the P3.1 combat scenario suite. Creatures reach the
 * battlefield by construction (real creature cards and permanent-spell resolution are P3.2), so a
 * scenario builds the battlefield directly and drives the engine through the combat decisions.
 *
 * Every creature here is a synthetic fixture (a bare [CardDefinition]: printed types, P/T, and
 * keywords, no mana cost — these objects are never cast). Distinct names per scenario keep the
 * name-based decision helpers unambiguous.
 */

// A synthetic creature definition: creature-typed, with printed [power]/[toughness] and [keywords].
private fun creature(
    name: String,
    power: Int,
    toughness: Int,
    vararg keywords: Keyword,
): CardDefinition =
    object : CardDefinition {
        override val characteristics =
            PrintedCharacteristics(
                name = name,
                manaCost = null,
                supertypes = persistentSetOf(),
                cardTypes = persistentSetOf(CardType.CREATURE),
                subtypes = persistentSetOf(),
                powerToughness = PrintedPowerToughness(power, toughness),
                keywords = persistentSetOf(*keywords),
            )
    }

/** The scenario creature catalog, keyed by ref (the registry combat scenarios build states with). */
internal val combatDefinitions: Map<CardRef, CardDefinition> =
    listOf(
        creature("Bear", 2, 2),
        creature("Ogre", 3, 3),
        creature("Giant", 4, 4),
        creature("Colossus", 6, 6),
        creature("Wall", 0, 4),
        creature("Flyer", 2, 2, Keyword.FLYING),
        creature("Raptor", 3, 3, Keyword.FLYING),
        creature("Striker", 2, 2, Keyword.FIRST_STRIKE),
        creature("Sentinel", 2, 2, Keyword.VIGILANCE),
    ).associateBy { CardRef(it.characteristics.name) }

/**
 * One handcrafted battlefield creature. [summoningSick] defaults to `false` — combat scenarios
 * usually want ready attackers; set it `true` to exercise the CR 302.6 exclusion.
 */
internal data class Combatant(
    val name: String,
    val tapped: Boolean = false,
    val summoningSick: Boolean = false,
    val damageMarked: Int = 0,
)

/**
 * A handcrafted, paused two-player [GameState] with the combat creature registry: [aliceField] and
 * [bobField] on the battlefield (ids allocated alice-first, in order), each seat on 20 life with a
 * few inert Mountains in library so an incidental draw never decks a scenario out, and [holder]
 * mid-priority (or no holder — the paused-at-a-turn-based-action shape combat uses at
 * declare-attackers/blockers).
 */
internal fun handcraftedCombat(
    turn: Turn,
    aliceField: List<Combatant> = emptyList(),
    bobField: List<Combatant> = emptyList(),
    holder: PlayerId? = null,
): GameState {
    var nextId = 0L

    fun objects(
        field: List<Combatant>,
        owner: PlayerId,
    ): List<GameObject> =
        field.map { spec ->
            GameObject(
                id = ObjectId(nextId++),
                card = CardRef(spec.name),
                owner = owner,
                tapped = spec.tapped,
                damageMarked = spec.damageMarked,
                summoningSick = spec.summoningSick,
            )
        }

    val aliceObjects = objects(aliceField, alice)
    val bobObjects = objects(bobField, bob)

    fun seat(owner: PlayerId): PlayerState =
        PlayerState(
            life = STARTING_LIFE,
            library = List(3) { GameObject(ObjectId(nextId++), CardRef("Mountain"), owner) }.toPersistentList(),
            hand = persistentListOf(),
            graveyard = persistentListOf(),
            priorityStatus = if (owner == holder) PriorityStatus.HOLDS_PRIORITY else PriorityStatus.NONE,
        )

    val alicePlayer = seat(alice)
    val bobPlayer = seat(bob)
    return GameState(
        players = persistentMapOf(alice to alicePlayer, bob to bobPlayer),
        turn = turn,
        sharedZones =
            SharedZones(
                battlefield = (aliceObjects + bobObjects).toPersistentList(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = combatDefinitions.toPersistentMap(),
    )
}

/** A state paused at the active player's declare-attackers turn-based action (CR 508.1). */
internal fun attackStep(
    aliceField: List<Combatant> = emptyList(),
    bobField: List<Combatant> = emptyList(),
    active: PlayerId = alice,
): GameState =
    handcraftedCombat(
        turn = Turn(active, TURN_NUMBER, TurnPhase.COMBAT, TurnStep.DECLARE_ATTACKERS),
        aliceField = aliceField,
        bobField = bobField,
    )

/** A sampled turn number that is neither turn 1 (no draw-step skip interactions) nor a boundary. */
internal const val TURN_NUMBER: Int = 3

// --- Decision helpers (name-based; scenario creature names are distinct within a request) ---

/** Declares the named creatures as attackers (CR 508.1); no names is the empty, legal declaration. */
internal fun DecisionRequest.DeclareAttackers.declaring(vararg names: String): Decision.MultiSelect =
    Decision.MultiSelect(id, names.map { name -> optionIndex(name) })

/** The eligible-attacker card names, in option order (for enumeration-completeness assertions). */
internal fun DecisionRequest.DeclareAttackers.attackerNames(): List<String> = options.map { it.card.name }

private fun DecisionRequest.DeclareAttackers.optionIndex(name: String): Int =
    options.indexOfFirst { it.card == CardRef(name) }.also {
        require(it >= 0) { "no eligible attacker named $name in ${attackerNames()}" }
    }

/** Declares the given (blocker name -> attacker name) blocks (CR 509.1); no pairs blocks nothing. */
internal fun DecisionRequest.DeclareBlockers.blocking(vararg pairs: Pair<String, String>): Decision.MultiSelect =
    Decision.MultiSelect(
        id,
        pairs.map { (blocker, attacker) ->
            options.indexOfFirst { it.blockerCard == CardRef(blocker) && it.attackerCard == CardRef(attacker) }.also {
                require(it >= 0) { "no legal block of $attacker by $blocker in ${blockPairs()}" }
            }
        },
    )

/** The legal (blocker, attacker) pairings by name (for enumeration assertions). */
internal fun DecisionRequest.DeclareBlockers.blockPairs(): List<Pair<String, String>> =
    options.map { it.blockerCard.name to it.attackerCard.name }

/** Orders this attacker's blockers by name — the permutation damage is assigned in (CR 509.2). */
internal fun DecisionRequest.OrderBlockers.ordering(vararg names: String): Decision.MultiSelect =
    Decision.MultiSelect(
        id,
        names.map { name ->
            options.indexOfFirst { it.card == CardRef(name) }.also {
                require(it >= 0) { "no blocker named $name to order in ${options.map { o -> o.card.name }}" }
            }
        },
    )

/** The battlefield creature with the given card [name]; scenario names are distinct there. */
internal fun GameState.creature(name: String): GameObject = sharedZones.battlefield.first { it.card == CardRef(name) }

/**
 * Passes the open two-player priority window to its end (both players pass in succession,
 * CR 117.4) and returns the next pause — a combat scenario's way to step over the priority round
 * that follows each combat sub-action, onto the next combat decision or step.
 */
internal fun DefaultGameEngine.passPriorityRound(result: AdvanceResult): AdvanceResult {
    val afterActive = advance(result.pausedState, passDecision(result.pending()))
    return advance(afterActive.pausedState, passDecision(afterActive.pending()))
}

/**
 * Drives forward with the do-nothing policy — pass every priority window, declare no attackers or
 * blockers, discard the lowest-indexed cards — until [until] holds of the paused state. A blunt
 * walker for reaching a later turn position from a handcrafted start.
 */
internal fun DefaultGameEngine.driveByPassing(
    from: AdvanceResult,
    until: (GameState) -> Boolean,
): AdvanceResult {
    var result = from
    var guard = 0
    while (!until(result.pausedState)) {
        check(guard++ < DRIVE_GUARD) { "driveByPassing did not reach its predicate within $DRIVE_GUARD steps" }
        result =
            when (val request = result.pending<DecisionRequest>()) {
                is DecisionRequest.ChooseAction -> advance(result.pausedState, passDecision(request))
                is DecisionRequest.DeclareAttackers -> advance(result.pausedState, request.declaring())
                is DecisionRequest.DeclareBlockers -> advance(result.pausedState, request.blocking())
                is DecisionRequest.ChooseDiscards -> {
                    val all = (0 until request.count).toList()
                    advance(result.pausedState, Decision.MultiSelect(request.id, all))
                }
                else -> error("driveByPassing does not handle $request")
            }
    }
    return result
}

private const val DRIVE_GUARD: Int = 200

/** Declares the named [attackers] from a declare-attackers-paused [state] (CR 508.1). */
internal fun DefaultGameEngine.declareAttackers(
    state: GameState,
    vararg attackers: String,
): AdvanceResult = advance(state, pausedRequestOf<DecisionRequest.DeclareAttackers>(state).declaring(*attackers))

/**
 * Declares the named [attackers] and passes the ensuing priority round, returning the pause at the
 * defending player's declare-blockers decision (CR 509.1) — the common preamble of the scenarios.
 */
internal fun DefaultGameEngine.toDeclareBlockers(
    state: GameState,
    vararg attackers: String,
): AdvanceResult = passPriorityRound(declareAttackers(state, *attackers))

/** Declares the given (blocker -> attacker) blocks from a [from] declare-blockers pause. */
internal fun DefaultGameEngine.declareBlocks(
    from: AdvanceResult,
    vararg pairs: Pair<String, String>,
): AdvanceResult = advance(from.pausedState, from.pending<DecisionRequest.DeclareBlockers>().blocking(*pairs))
