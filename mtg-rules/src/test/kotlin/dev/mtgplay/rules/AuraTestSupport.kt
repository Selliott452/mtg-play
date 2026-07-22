package dev.mtgplay.rules

import dev.mtgplay.core.definition.SpellDefinition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.StackEntry
import dev.mtgplay.core.state.Target
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/*
 * Handcrafted-state builders for the P4.1 layer specs. Auras and their enchanted objects reach the
 * battlefield by construction for the unit-level layer/SBA tests; the casting specs drive the engine
 * through the real CR 601 pipeline from a mid-priority handcrafted start.
 */

/** A battlefield object fixture: [name] resolves via [auraDefinitions]; [attachedTo] is an id. */
internal fun bfObject(
    id: Long,
    name: String,
    owner: PlayerId = alice,
    attachedTo: Long? = null,
    damageMarked: Int = 0,
): GameObject =
    GameObject(
        id = ObjectId(id),
        card = CardRef(name),
        owner = owner,
        attachedTo = attachedTo?.let(::ObjectId),
        damageMarked = damageMarked,
    )

/**
 * A paused two-player [GameState] over the [auraDefinitions] registry with [battlefield] in place,
 * both seats at 20 life. [holder] (if given) is mid-priority; otherwise no player holds priority.
 * The id counter sits just past the highest battlefield id.
 */
internal fun auraState(
    battlefield: List<GameObject>,
    turn: Turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
    holder: PlayerId? = null,
): GameState {
    fun seat(owner: PlayerId) =
        PlayerState(
            life = STARTING_LIFE,
            library = persistentListOf(),
            hand = persistentListOf(),
            graveyard = persistentListOf(),
            priorityStatus = if (owner == holder) PriorityStatus.HOLDS_PRIORITY else PriorityStatus.NONE,
        )
    val nextId = (battlefield.maxOfOrNull { it.id.value } ?: -1L) + 1
    return GameState(
        players = persistentMapOf(alice to seat(alice), bob to seat(bob)),
        turn = turn,
        sharedZones =
            SharedZones(
                battlefield = battlefield.toPersistentList(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = auraDefinitions.toPersistentMap(),
    )
}

/**
 * A mid-priority state (alice holds priority in her precombat main) with [aliceHand] in hand and
 * [battlefield] in place — the shape the aura-casting specs drive. Hand ids follow the battlefield's.
 */
internal fun auraCastingState(
    aliceHand: List<String>,
    battlefield: List<GameObject>,
): GameState {
    var nextId = (battlefield.maxOfOrNull { it.id.value } ?: -1L) + 1
    val hand =
        aliceHand.map { name -> GameObject(ObjectId(nextId), CardRef(name), alice).also { nextId += 1 } }

    fun seat(
        owner: PlayerId,
        handObjects: List<GameObject>,
    ) = PlayerState(
        life = STARTING_LIFE,
        library = persistentListOf(),
        hand = handObjects.toPersistentList(),
        graveyard = persistentListOf(),
        priorityStatus = if (owner == alice) PriorityStatus.HOLDS_PRIORITY else PriorityStatus.NONE,
    )
    return GameState(
        players = persistentMapOf(alice to seat(alice, hand), bob to seat(bob, emptyList())),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = battlefield.toPersistentList(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = nextId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = auraDefinitions.toPersistentMap(),
    )
}

/** The single battlefield object with card [name] (fixture names are distinct within a test). */
internal fun GameState.bf(name: String): GameObject = sharedZones.battlefield.first { it.card == CardRef(name) }

/**
 * A state with the Aura [auraName] (id [auraId]) on the stack targeting the object [targetId]
 * (CR 601.2c), over [battlefield] and the [auraDefinitions] registry — the shape a resolveTopOfStack
 * test starts from. No player holds priority (resolution happens after all pass).
 */
internal fun auraStackState(
    battlefield: List<GameObject>,
    auraName: String,
    auraId: Long,
    targetId: Long,
    controller: PlayerId = alice,
): GameState {
    val definition = auraDefinitions.getValue(CardRef(auraName)) as SpellDefinition
    val stackObject = GameObject(ObjectId(auraId), CardRef(auraName), controller)
    val entry =
        StackEntry.Spell(
            obj = stackObject,
            controller = controller,
            targets = persistentListOf(Target.Permanent(ObjectId(targetId))),
            definition = definition,
        )
    val ids = (battlefield.map { it.id.value } + auraId)

    fun seat() = PlayerState(STARTING_LIFE, persistentListOf(), persistentListOf(), persistentListOf())
    return GameState(
        players = persistentMapOf(alice to seat(), bob to seat()),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = battlefield.toPersistentList(),
                stack = persistentListOf(entry),
                exile = persistentListOf(),
            ),
        nextObjectId = ids.max() + 1,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = auraDefinitions.toPersistentMap(),
    )
}
