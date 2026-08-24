package dev.mtgplay.rules

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.PriorityStatus
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.ManaActivation
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.decision.SymbolPayment
import dev.mtgplay.rules.engine.activationYield
import dev.mtgplay.rules.engine.expandToUnits
import dev.mtgplay.rules.engine.manaSourceClasses
import dev.mtgplay.rules.engine.payManaPlan
import dev.mtgplay.rules.engine.paymentSatisfies
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/*
 * The brute-force oracle and the planner/executor correspondence check of
 * docs/design/mana-payment.md §10, plus the scenario states both run against. The oracle shares
 * only the vocabulary (symbol expansion, symbol satisfaction, source classing) with the
 * enumerator: the search, the dedup rule, the coverage test and the no-idle bound are all
 * re-implemented naively here, which is what makes the comparison worth anything.
 */

/** One named scenario: the state to enumerate against and the cost to pay. */
internal typealias PaymentScenario = Pair<GameState, ManaCost>

/**
 * The scenarios the completeness, correspondence, purity and bound properties all run over: every
 * MVP cost shape against ordinary sources, plus the P8.3 ramp cases where one activation yields
 * two mana (CR 605.1b) and the pooled cases where floating mana competes with activating.
 */
internal fun oracleScenarios(): List<Pair<String, PaymentScenario>> =
    listOf(
        "{R} over three Mountains" to
            (fixtureBoard(SeatSetup(battlefield = List(3) { "Fixture Mountain" })) to ManaCost.parse("{R}")),
        "{1}{R} over two Mountains and a Forest" to
            (
                fixtureBoard(
                    SeatSetup(battlefield = listOf("Fixture Mountain", "Fixture Mountain", "Fixture Forest")),
                ) to ManaCost.parse("{1}{R}")
            ),
        "{2} over a Mountain, a Forest and an any-color source" to
            (
                fixtureBoard(
                    SeatSetup(battlefield = listOf("Fixture Mountain", "Fixture Forest", "Fixture Prism")),
                ) to ManaCost.parse("{2}")
            ),
        "{G/U} over a Forest, an Island and an any-color source" to
            (
                fixtureBoard(
                    SeatSetup(battlefield = listOf("Fixture Forest", "Fixture Island", "Fixture Prism")),
                ) to ManaCost.parse("{G/U}")
            ),
        "{R/P}{R/P} at 3 life over one Mountain" to
            (
                fixtureBoard(SeatSetup(battlefield = listOf("Fixture Mountain"), life = 3)) to
                    ManaCost.parse("{R/P}{R/P}")
            ),
        "{C}{1} over Wastes and a Mountain" to
            (
                fixtureBoard(SeatSetup(battlefield = listOf("Fixture Wastes", "Fixture Mountain"))) to
                    ManaCost.parse("{C}{1}")
            ),
        "{0} over a Mountain" to
            (fixtureBoard(SeatSetup(battlefield = listOf("Fixture Mountain"))) to ManaCost.parse("{0}")),
        "{R} over a Mountain with a red already floating" to
            (
                fixtureBoard(SeatSetup(battlefield = listOf("Fixture Mountain"))).withPool(ManaType.RED) to
                    ManaCost.parse("{R}")
            ),
        "{1}{G} off one green-chosen ramp Forest" to (rampState(Color.GREEN) to ManaCost.parse("{1}{G}")),
        "{1}{G}{G} off two ramp Forests and a bare Forest" to
            (rampState(Color.GREEN, ramps = 2, bareForests = 1) to ManaCost.parse("{1}{G}{G}")),
        "{2} off a red-chosen ramp Forest and a Mountain" to
            (rampState(Color.RED, mountains = 1) to ManaCost.parse("{2}")),
        "{1}{G} off a ramp Forest with a green already floating" to
            (rampState(Color.GREEN).withPool(ManaType.GREEN) to ManaCost.parse("{1}{G}")),
        "{G} off a fixed-mana ramp Forest" to (fixedRampState() to ManaCost.parse("{G}")),
        "{1}{G} off a fixed-mana ramp Forest" to (fixedRampState() to ManaCost.parse("{1}{G}")),
    )

/**
 * Every legal plan for [cost] in [state], found by naive brute force: every raw assignment of a
 * satisfying payment to each expanded symbol, crossed with every activation multiset up to the
 * length bound, kept when the life bound, the coverage test and the no-idle rule all hold.
 */
internal fun oraclePlans(
    state: GameState,
    cost: ManaCost,
): Set<CanonicalPlan> {
    val units = expandToUnits(cost)
    val classes = manaSourceClasses(state, alice)
    val player = state.players.getValue(alice)
    val pool = player.manaPool.groupingBy { it }.eachCount()
    val options = classes.flatMapIndexed { index, c -> c.key.profile.map { index to ManaActivation(c.key, it) } }
    val capacity = classes.map { it.members.size }
    val rawPayments =
        units.map { symbol ->
            (ManaType.entries.map { SymbolPayment.WithMana(it) } + SymbolPayment.WithTwoLife)
                .filter { paymentSatisfies(symbol, it) }
        }
    val found = mutableSetOf<CanonicalPlan>()
    cartesianProduct(rawPayments).forEach { payments ->
        val lifePaid = payments.count { it == SymbolPayment.WithTwoLife } * PHYREXIAN_LIFE
        if (lifePaid > player.life) return@forEach
        val demand = payments.filterIsInstance<SymbolPayment.WithMana>().map { it.mana }
        activationMultisets(options, capacity, demand.size).forEach { activations ->
            if (oracleCovers(pool, activations, demand) && oracleSpendsEvery(activations, demand)) {
                found += canonicalForm(cost, PaymentPlan(activations, payments))
            }
        }
    }
    return found
}

/** A plan's [CanonicalPlan] identity: activations as a multiset, payments as a per-run multiset. */
internal fun canonicalForm(
    cost: ManaCost,
    plan: PaymentPlan,
): CanonicalPlan {
    val units = expandToUnits(cost)
    val runIndex = IntArray(units.size)
    var run = 0
    units.forEachIndexed { index, symbol ->
        if (index > 0 && symbol != units[index - 1]) run += 1
        runIndex[index] = run
    }
    return CanonicalPlan(
        activations = plan.activations.groupingBy { it }.eachCount(),
        payments =
            plan.payments
                .withIndex()
                .groupBy({ runIndex[it.index] }, { it.value })
                .mapValues { (_, payments) -> payments.groupingBy { it }.eachCount() },
    )
}

/**
 * Executes [plan] for [cost] and asserts it did exactly what it declared (CR 601.2g–h): the pool
 * afterwards is the pool before, plus every activation's yield, minus every mana payment — with no
 * count going negative — and life fell by 2 per Phyrexian life payment (CR 107.4).
 */
internal fun assertExecutesAsDeclared(
    state: GameState,
    cost: ManaCost,
    plan: PaymentPlan,
) {
    val before = state.players.getValue(alice)
    val produced = plan.activations.flatMap { activationYield(it.sourceClass, it.produced) }
    val expected = (before.manaPool.toList() + produced).groupingBy { it }.eachCount().toMutableMap()
    plan.payments.filterIsInstance<SymbolPayment.WithMana>().forEach { payment ->
        expected[payment.mana] = (expected[payment.mana] ?: 0) - 1
    }
    expected.forEach { (type, count) -> withClue(type) { count shouldBeGreaterThanOrEqual 0 } }

    val after = payManaPlan(state, alice, cost, plan).players.getValue(alice)
    after.manaPool.groupingBy { it }.eachCount() shouldBe expected.filterValues { it > 0 }
    after.life shouldBe before.life - PHYREXIAN_LIFE * plan.payments.count { it == SymbolPayment.WithTwoLife }
}

/** The state with [mana] added to alice's pool, standing in for mana floated earlier in the step. */
internal fun GameState.withPool(mana: ManaType): GameState =
    copy(
        players =
            players.putting(
                alice,
                players.getValue(alice).copy(manaPool = players.getValue(alice).manaPool.adding(mana)),
            ),
    )

// ---- scenario boards ---------------------------------------------------------------------------

private fun fixtureBoard(setup: SeatSetup): GameState = fixtureState(aliceSetup = setup, bobSetup = SeatSetup())

/**
 * A board of [ramps] Forests each enchanted by a chosen-colour ramp Aura that named [chosen]
 * (CR 605.1b), plus [bareForests] unenchanted Forests and [mountains] Mountains. One activation of
 * a ramp Forest yields its own `{G}` **and** the Aura's additional mana.
 */
internal fun rampState(
    chosen: Color,
    ramps: Int = 1,
    bareForests: Int = 0,
    mountains: Int = 0,
): GameState {
    var nextId = 0L
    val battlefield = mutableListOf<GameObject>()
    repeat(ramps) {
        val forest = GameObject(ObjectId(nextId++), CardRef("Fixture Forest"), alice)
        battlefield += forest
        battlefield +=
            GameObject(
                ObjectId(nextId++),
                CardRef("Fixture Chosen Ramp"),
                alice,
                attachedTo = forest.id,
                chosenColor = chosen,
            )
    }
    repeat(bareForests) { battlefield += GameObject(ObjectId(nextId++), CardRef("Fixture Forest"), alice) }
    repeat(mountains) { battlefield += GameObject(ObjectId(nextId++), CardRef("Fixture Mountain"), alice) }
    return rampGameState(battlefield, nextId)
}

/** A board of one Forest enchanted by a *printed*-mana ramp Aura (Wild Growth's shape, CR 605.1b). */
internal fun fixedRampState(): GameState {
    val forest = GameObject(ObjectId(0), CardRef("Fixture Forest"), alice)
    val aura = GameObject(ObjectId(1), CardRef("Fixture Fixed Ramp"), alice, attachedTo = forest.id)
    return rampGameState(listOf(forest, aura), 2)
}

private fun rampGameState(
    battlefield: List<GameObject>,
    nextObjectId: Long,
): GameState =
    GameState(
        players =
            persistentMapOf(
                alice to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = persistentListOf(),
                        graveyard = persistentListOf(),
                        priorityStatus = PriorityStatus.HOLDS_PRIORITY,
                    ),
                bob to
                    PlayerState(
                        life = STARTING_LIFE,
                        library = persistentListOf(),
                        hand = persistentListOf(),
                        graveyard = persistentListOf(),
                    ),
            ),
        turn = Turn(alice, 3, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones = SharedZones(battlefield.toPersistentList(), persistentListOf(), persistentListOf()),
        nextObjectId = nextObjectId,
        rng = Rng(0),
        events = persistentListOf(),
        definitions = (fixtureDefinitions + rampAuraFixtures).toPersistentMap(),
    )

// ---- the naive halves --------------------------------------------------------------------------

private const val PHYREXIAN_LIFE: Int = 2

private fun cartesianProduct(candidates: List<List<SymbolPayment>>): List<List<SymbolPayment>> =
    candidates.fold(listOf(emptyList())) { acc, options ->
        acc.flatMap { prefix -> options.map { prefix + it } }
    }

/** Every non-decreasing selection of at most [maxSize] options, respecting each class's capacity. */
private fun activationMultisets(
    options: List<Pair<Int, ManaActivation>>,
    capacity: List<Int>,
    maxSize: Int,
): List<List<ManaActivation>> {
    val found = mutableListOf<List<ManaActivation>>()

    fun walk(
        from: Int,
        chosen: List<Pair<Int, ManaActivation>>,
    ) {
        found += chosen.map { it.second }
        if (chosen.size == maxSize) return
        for (index in from until options.size) {
            val option = options[index]
            if (chosen.count { it.first == option.first } >= capacity[option.first]) continue
            walk(index, chosen + option)
        }
    }
    walk(0, emptyList())
    return found
}

/** Whether the pool plus the activations' yields meet the demanded mana, type by type. */
private fun oracleCovers(
    pool: Map<ManaType, Int>,
    activations: List<ManaActivation>,
    demand: List<ManaType>,
): Boolean {
    val available = pool.toMutableMap()
    activations.forEach { activation ->
        activationYield(activation.sourceClass, activation.produced).forEach {
            available[it] = (available[it] ?: 0) + 1
        }
    }
    return demand.groupingBy { it }.eachCount().all { (type, count) -> count <= (available[type] ?: 0) }
}

/**
 * Whether every activation spends at least one of its mana, found by exhaustive assignment rather
 * than by the enumerator's Hall check — the point of an oracle is to agree by a different route.
 */
private fun oracleSpendsEvery(
    activations: List<ManaActivation>,
    demand: List<ManaType>,
): Boolean {
    val remaining = demand.groupingBy { it }.eachCount().toMutableMap()

    fun assign(index: Int): Boolean {
        if (index == activations.size) return true
        val activation = activations[index]
        return activationYield(activation.sourceClass, activation.produced).distinct().any { type ->
            val left = remaining[type] ?: 0
            if (left == 0) {
                false
            } else {
                remaining[type] = left - 1
                val ok = assign(index + 1)
                remaining[type] = left
                ok
            }
        }
    }
    return assign(0)
}
