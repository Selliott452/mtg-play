package dev.mtgplay.acceptance

import dev.mtgplay.cards.MvpCards
import dev.mtgplay.core.definition.CardDefinition
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.identity.PlayerId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/*
 * The seeded random-board generator for the P4.3 layer property suite (docs/design/layer-system.md §8),
 * over the real seven Bogles Auras and the [MvpCards] creatures/lands — no fixture cards. All
 * randomness flows through the seeded core [Rng] (ADR-006), so a board is a deterministic pure function
 * of its seed and the corpus replays identically.
 *
 * A board is a set of hosts (creatures and lands) with a random number of legal Auras attached to each:
 * creature Auras on creatures, Abundant Growth on lands, multiple Auras per host allowed, controllers
 * varied across the two seats (so Ethereal Armor's "enchantments you control" count and Cartouche's
 * control restriction are both exercised). The unattached-Aura edge is excluded by construction: at a
 * game pause a dangling Aura is illegal (CR 704.5m), so every generated Aura enters attached to a legal
 * host. Object ids are minted host-then-its-Auras, so an Aura's id (its CR 613.7c timestamp, §3) follows
 * battlefield-entry order exactly as the engine sources it.
 */

/** Creatures from [MvpCards] to enchant (CR 302): a vanilla 2/2 up to a 1/4, some with a printed keyword. */
private val CREATURE_HOSTS =
    listOf("Grizzly Bears", "Hill Giant", "Wind Drake", "Youthful Knight", "Standing Troops")

/** Lands from [MvpCards] (CR 305): the objects Abundant Growth enchants. */
private val LAND_HOSTS = listOf("Forest", "Mountain", "Plains")

/** Auras that may enchant ANY creature (CR 303.4a, no control clause) — fixed and dynamic +X/+Y and grants. */
private val ANY_CREATURE_AURAS =
    listOf("Rancor", "Armadillo Cloak", "Sentinel's Eyes", "Ethereal Armor", "Ancestral Mask")

/** The one Aura restricted to a creature its controller controls (CR 303.4a); control == ownership (§4). */
private const val CREATURE_YOU_CONTROL_AURA = "Cartouche of Solidarity"

/** The one Aura that enchants a land (CR 303.4a) — the layer-6 any-color mana-ability grant. */
private const val LAND_AURA = "Abundant Growth"

/** The two seats an object may belong to; control is ownership in the MVP pool (§4). */
private val OWNERS = listOf(alice, bob)

private const val MIN_HOSTS = 3

/** Draw span for host count: `MIN_HOSTS + [0, HOST_COUNT_SPAN)` gives 3..7 hosts. */
private const val HOST_COUNT_SPAN = 5

/** ~70% of hosts are creatures: a `below(HOST_WEIGHT_DENOMINATOR)` under this numerator is a creature. */
private const val CREATURE_HOST_NUMERATOR = 7
private const val HOST_WEIGHT_DENOMINATOR = 10

/** A creature carries `[0, MAX_CREATURE_AURAS_EXCLUSIVE)` Auras — 0..3, so multi-Aura stacks occur. */
private const val MAX_CREATURE_AURAS_EXCLUSIVE = 4

/** A land carries `[0, MAX_LAND_AURAS_EXCLUSIVE)` Abundant Growths — 0..2. */
private const val MAX_LAND_AURAS_EXCLUSIVE = 3

/** A seeded draw source threading the core [Rng] (ADR-006) across the generator's choices. */
private class SeededDraw(
    seed: Long,
) {
    private var rng: Rng = Rng(seed)

    /** A uniform value in `[0, bound)`, advancing the generator (ADR-006). */
    fun below(bound: Int): Int {
        val (value, next) = rng.nextInt(bound)
        rng = next
        return value
    }

    /** A uniformly chosen element of [options]. */
    fun <T> pick(options: List<T>): T = options[below(options.size)]
}

/**
 * A deterministic random battlefield for [seed] (docs/design/layer-system.md §8): hosts with legal Auras
 * attached, over the real [MvpCards] pool. Pure in [seed] via the seeded [Rng] (ADR-006).
 */
internal fun randomBoard(seed: Long): GameState {
    val draw = SeededDraw(seed)
    val objects = mutableListOf<GameObject>()
    var nextId = 0L
    repeat(MIN_HOSTS + draw.below(HOST_COUNT_SPAN)) {
        val hostOwner = draw.pick(OWNERS)
        val hostId = ObjectId(nextId)
        nextId += 1
        if (draw.below(HOST_WEIGHT_DENOMINATOR) < CREATURE_HOST_NUMERATOR) {
            objects += GameObject(hostId, CardRef(draw.pick(CREATURE_HOSTS)), hostOwner, summoningSick = false)
            repeat(draw.below(MAX_CREATURE_AURAS_EXCLUSIVE)) {
                val auraOwner = draw.pick(OWNERS)
                val auraName = pickCreatureAura(draw, auraOwner, hostOwner)
                objects += GameObject(ObjectId(nextId), CardRef(auraName), auraOwner, attachedTo = hostId)
                nextId += 1
            }
        } else {
            objects += GameObject(hostId, CardRef(draw.pick(LAND_HOSTS)), hostOwner)
            repeat(draw.below(MAX_LAND_AURAS_EXCLUSIVE)) {
                val auraOwner = draw.pick(OWNERS)
                objects += GameObject(ObjectId(nextId), CardRef(LAND_AURA), auraOwner, attachedTo = hostId)
                nextId += 1
            }
        }
    }
    return layerBoard(objects)
}

/**
 * A legal creature Aura for an Aura controlled by [auraOwner] enchanting a creature controlled by
 * [hostOwner] (CR 303.4a): Cartouche of Solidarity ("creature you control") is available only when the
 * two match (control == ownership, §4); otherwise only the no-control-clause creature Auras.
 */
private fun pickCreatureAura(
    draw: SeededDraw,
    auraOwner: PlayerId,
    hostOwner: PlayerId,
): String {
    val pool = if (auraOwner == hostOwner) ANY_CREATURE_AURAS + CREATURE_YOU_CONTROL_AURA else ANY_CREATURE_AURAS
    return draw.pick(pool)
}

/**
 * A paused two-player [GameState] with [objects] on the battlefield and the given [definitions]
 * registry (the real [MvpCards] by default), both seats at 20 life — a valid engine input by
 * construction (ADR-004). The id counter sits just past the highest battlefield id.
 */
internal fun layerBoard(
    objects: List<GameObject>,
    definitions: Map<CardRef, CardDefinition> = MvpCards.definitions,
): GameState {
    fun seat() = PlayerState(STARTING_LIFE, persistentListOf(), persistentListOf(), persistentListOf())
    return GameState(
        players = persistentMapOf(alice to seat(), bob to seat()),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = objects.toPersistentList(),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = (objects.maxOfOrNull { it.id.value } ?: -1L) + 1,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = definitions.toPersistentMap(),
    )
}

/** The ids of every attached Aura on [state]'s battlefield, in entry order (their CR 613.7c timestamps, §3). */
internal fun auraIdsInEntryOrder(state: GameState): List<ObjectId> =
    state.sharedZones.battlefield
        .filter { it.attachedTo != null }
        .map { it.id }

/**
 * The same board with its Auras' timestamps permuted: each attached Aura keeps its card, controller, and
 * attachment but is reassigned an id from [permutedIds] (a bijection over the current Aura ids, in
 * battlefield order). Because an Aura's id *is* its CR 613.7c timestamp (§3), this is a genuine
 * timestamp permutation — the auras entered in a different order — and nothing else about the board
 * changes. The host ids are untouched, so a host's characteristics can be compared across permutations.
 */
internal fun withAuraTimestamps(
    state: GameState,
    permutedIds: List<ObjectId>,
): GameState {
    val battlefield = state.sharedZones.battlefield
    val auraPositions = battlefield.indices.filter { battlefield[it].attachedTo != null }
    require(permutedIds.size == auraPositions.size) { "a timestamp permutation assigns exactly one id per Aura" }
    require(permutedIds.toSet() == auraPositions.map { battlefield[it].id }.toSet()) {
        "a timestamp permutation must be a bijection over the Aura ids (CR 613.7 / §3)"
    }
    val relocated = battlefield.toMutableList()
    auraPositions.forEachIndexed { index, position ->
        relocated[position] = relocated[position].copy(id = permutedIds[index])
    }
    return state.copy(sharedZones = state.sharedZones.copy(battlefield = relocated.toPersistentList()))
}
