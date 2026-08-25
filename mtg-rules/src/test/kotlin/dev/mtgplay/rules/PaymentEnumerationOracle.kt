package dev.mtgplay.rules

import dev.mtgplay.core.definition.ManaAbilityCost
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
import dev.mtgplay.rules.decision.ProductionAlternative
import dev.mtgplay.rules.decision.SymbolPayment
import dev.mtgplay.rules.engine.activationYield
import dev.mtgplay.rules.engine.expandToUnits
import dev.mtgplay.rules.engine.isCreature
import dev.mtgplay.rules.engine.manaSourceClasses
import dev.mtgplay.rules.engine.payManaPlan
import dev.mtgplay.rules.engine.paymentSatisfies
import dev.mtgplay.rules.engine.sourceClassKeyOf
import dev.mtgplay.rules.engine.untappedCreatures
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
    ) + creatureSourceScenarios() + boardDependentSourceScenarios() + costedSourceScenarios()

/**
 * The `FW-MANACOST` scenarios: sources whose mana ability *costs* something, so an activation is a
 * consumer as well as a producer. They are what re-runs the completeness oracle, the correspondence
 * property and the bound property against a search whose legality now includes coverage-net-of-cost,
 * an execution order, a creature budget and a self-exclusion matching (docs/design/mana-payment.md
 * §11) — none of which the pre-packet oracle could have distinguished from a stuck enumerator.
 *
 * They span the four shapes that differ, and every one of them is a board a gauntlet deck can build:
 * a costed filter that must be funded from the pool, **two** of them (the cycle the model must
 * refuse), a source printing a free ability beside a costed one, the creature-tapping cost, and the
 * once-each-turn counter cost.
 */
private fun costedSourceScenarios(): List<Pair<String, PaymentScenario>> =
    listOf(
        "{R} off a costed filter with a green floating" to
            (
                fixtureBoard(SeatSetup(battlefield = listOf("Fixture Filter"))).withPool(ManaType.GREEN) to
                    ManaCost.parse("{R}")
            ),
        // The acyclicity case: two filters, empty pool, and no order in which either can go first.
        "{R} off two costed filters on an empty pool" to
            (
                fixtureBoard(SeatSetup(battlefield = listOf("Fixture Filter", "Fixture Filter"))) to
                    ManaCost.parse("{R}")
            ),
        // The chain: one Forest funds one filter, and the filter's output funds the other.
        "{R}{W} off two costed filters and a Forest" to
            (
                fixtureBoard(
                    SeatSetup(battlefield = listOf("Fixture Filter", "Fixture Filter", "Fixture Forest")),
                ) to ManaCost.parse("{R}{W}")
            ),
        "{1}{R} off a free-and-costed source beside a Mountain" to
            (
                fixtureBoard(SeatSetup(battlefield = listOf("Fixture Pylon Gate", "Fixture Mountain"))) to
                    ManaCost.parse("{1}{R}")
            ),
        "{W} off two Caretakers and nothing else" to
            (
                fixtureBoard(SeatSetup(battlefield = listOf("Fixture Caretaker", "Fixture Caretaker")))
                    .settled() to ManaCost.parse("{W}")
            ),
        "{W}{W} off two Caretakers and nothing else" to
            (
                fixtureBoard(SeatSetup(battlefield = listOf("Fixture Caretaker", "Fixture Caretaker")))
                    .settled() to ManaCost.parse("{W}{W}")
            ),
        "{W}{U} off two Caretakers and two mana Elves" to
            (
                fixtureBoard(
                    SeatSetup(
                        battlefield =
                            listOf("Fixture Caretaker", "Fixture Caretaker", "Fixture Mana Elf", "Fixture Mana Elf"),
                    ),
                ).settled() to ManaCost.parse("{W}{U}")
            ),
        // CR 602.5a restricts {T} costs only, so a summoning-sick Wall taps for mana the turn it lands.
        "{G} off a summoning-sick counter-cost Wall" to
            (fixtureBoard(SeatSetup(battlefield = listOf("Fixture Wall"))) to ManaCost.parse("{G}")),
        "{G}{G} off two counter-cost Walls" to
            (
                fixtureBoard(SeatSetup(battlefield = listOf("Fixture Wall", "Fixture Wall"))) to
                    ManaCost.parse("{G}{G}")
            ),
    )

/**
 * The `FW-MANA` scenarios: sources whose production is read off the board when the ability resolves
 * (CR 605.2), so the profile the plan was enumerated against is itself state-derived. They are what
 * re-runs the completeness oracle and the planner/executor correspondence property against a
 * **state-conditional** profile rather than a static one, which is the risk the packet carries
 * (docs/design/mana-payment.md §8.3).
 *
 * They deliberately span the three shapes that differ: a conditional amount that is 1 or 3, a
 * conditional amount that is 1 or 2 in the same plan, and a per-permanent count — plus the
 * cross-controller count and a competing pool, because those are where an off-by-one hides.
 */
private fun boardDependentSourceScenarios(): List<Pair<String, PaymentScenario>> {
    val assembled = listOf("Fixture Pylon", "Fixture Reactor")
    return listOf(
        "{C}{C}{C} off an assembled Pylon (three mana from one activation)" to
            (fixtureBoard(SeatSetup(battlefield = assembled)) to ManaCost.parse("{C}{C}{C}")),
        "{4} off an assembled Pylon and Reactor" to
            (fixtureBoard(SeatSetup(battlefield = assembled)) to ManaCost.parse("{4}")),
        "{C}{C}{C} off an assembled Pylon with a colorless already floating" to
            (
                fixtureBoard(SeatSetup(battlefield = assembled)).withPool(ManaType.COLORLESS) to
                    ManaCost.parse("{C}{C}{C}")
            ),
        // Unassembled, both lands add exactly one, so the whole conditional collapses to the
        // ordinary case — the control that proves the condition is read and not assumed.
        "{C}{C} off an unassembled Pylon and a Wastes" to
            (
                fixtureBoard(SeatSetup(battlefield = listOf("Fixture Pylon", "Fixture Wastes"))) to
                    ManaCost.parse("{C}{C}")
            ),
        "{G}{G} off two settled Elders, each counting both" to
            (
                fixtureBoard(SeatSetup(battlefield = listOf("Fixture Elder", "Fixture Elder"))).settled() to
                    ManaCost.parse("{G}{G}")
            ),
        // "for each Fixture Kin on the battlefield" spans both battlefields, so alice's lone Elder
        // adds two while bob's Elder is what makes the second one exist.
        "{G}{G} off one settled Elder counting the opponent's" to
            (
                fixtureState(
                    aliceSetup = SeatSetup(battlefield = listOf("Fixture Elder")),
                    bobSetup = SeatSetup(battlefield = listOf("Fixture Elder")),
                ).settled() to ManaCost.parse("{G}{G}")
            ),
    )
}

/**
 * The scenarios whose sources are **creatures** (CR 302), the only shape for which the CR 302.6
 * summoning-sickness gate on mana payment is observable. Kept apart from [oracleScenarios]' body
 * only so neither list outgrows a readable length.
 */
private fun creatureSourceScenarios(): List<Pair<String, PaymentScenario>> =
    listOf(
        "{1}{G} over two settled creature mana sources and a Forest" to
            (
                fixtureBoard(
                    SeatSetup(battlefield = listOf("Fixture Mana Elf", "Fixture Mana Elf", "Fixture Forest")),
                ).settled() to ManaCost.parse("{1}{G}")
            ),
        "{G} over a summoning-sick creature mana source and a Forest" to
            (
                fixtureBoard(SeatSetup(battlefield = listOf("Fixture Mana Elf", "Fixture Forest"))) to
                    ManaCost.parse("{G}")
            ),
        // CR 302.6 restricts {T} and {Q} costs only, so a sacrifice source is usable while sick.
        "{C} over a summoning-sick sacrifice creature source" to
            (fixtureBoard(SeatSetup(battlefield = listOf("Fixture Mana Spawn"))) to ManaCost.parse("{C}")),
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
    val options =
        classes.flatMapIndexed { index, c ->
            c.key.profile.flatMap { alternative ->
                oracleCostAssignments(alternative).map { index to ManaActivation(c.key, alternative, it) }
            }
        }
    val capacity = classes.map { it.members.size }
    // `FW-MANACOST`: a costed activation may claim another activation's cost rather than a unit of
    // demand, so the pre-packet bound (one activation per demanded mana) no longer holds. The oracle
    // takes the crudest sound bound instead — you cannot activate more sources than you control —
    // which is deliberately *not* the enumerator's tighter formula.
    val costed = classes.any { c -> c.key.profile.any { it.manaCost != null } }
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
        val bound = if (costed) capacity.sum() else demand.size
        activationMultisets(options, capacity, bound).forEach { activations ->
            val legal =
                oracleCovers(pool, activations, demand) &&
                    oracleSpendsEvery(activations, demand) &&
                    oracleOrderable(pool, activations) &&
                    oracleCreatureBudgetHolds(state, activations)
            if (legal) {
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
    val produced = plan.activations.flatMap { activationYield(it.sourceClass, it.alternative) }
    val expected = (before.manaPool.toList() + produced).groupingBy { it }.eachCount().toMutableMap()
    // `FW-MANACOST`: an activation may spend mana as well as add it, so the declared pool nets its
    // own cost out too. Execution reaches the pool through `resolveManaActivation`, which re-derives
    // the source class key from live state; the expectation reaches it through `activationYield` and
    // the plan's recorded `costPayment`. Two different routes to the same number.
    plan.activations.flatMap { it.costPayment }.forEach { spent ->
        expected[spent] = (expected[spent] ?: 0) - 1
    }
    plan.payments.filterIsInstance<SymbolPayment.WithMana>().forEach { payment ->
        expected[payment.mana] = (expected[payment.mana] ?: 0) - 1
    }
    expected.forEach { (type, count) -> withClue(type) { count shouldBeGreaterThanOrEqual 0 } }

    val after = payManaPlan(state, alice, cost, plan).players.getValue(alice)
    after.manaPool.groupingBy { it }.eachCount() shouldBe expected.filterValues { it > 0 }
    after.life shouldBe before.life - PHYREXIAN_LIFE * plan.payments.count { it == SymbolPayment.WithTwoLife }
}

/**
 * The state with no battlefield permanent summoning sick — the board as it stands after its
 * controller's untap step (CR 302.6). `fixtureState` builds battlefield objects with the
 * [dev.mtgplay.core.state.GameObject.summoningSick] default, which is right for a permanent that
 * just arrived and wrong for one that has been there since before the turn.
 */
internal fun GameState.settled(): GameState =
    copy(
        sharedZones =
            sharedZones.copy(
                battlefield = sharedZones.battlefield.map { it.copy(summoningSick = false) }.toPersistentList(),
            ),
    )

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

/**
 * Every assignment of mana to [alternative]'s own activation cost the oracle will consider — the raw
 * cartesian product over satisfying types, with no canonicalisation, which is the point: the
 * enumerator's non-decreasing rule has to be *proved* lossless, not assumed.
 */
private fun oracleCostAssignments(alternative: ProductionAlternative): List<List<ManaType>> {
    val mana = alternative.manaCost ?: return listOf(emptyList())
    return expandToUnits(mana.cost).fold(listOf(emptyList())) { acc, symbol ->
        val types = ManaType.entries.filter { paymentSatisfies(symbol, SymbolPayment.WithMana(it)) }
        acc.flatMap { prefix -> types.map { prefix + it } }
    }
}

/**
 * Whether the pool plus the activations' yields meet the demanded mana **and** the activations' own
 * costs, type by type.
 */
private fun oracleCovers(
    pool: Map<ManaType, Int>,
    activations: List<ManaActivation>,
    demand: List<ManaType>,
): Boolean {
    val available = pool.toMutableMap()
    activations.forEach { activation ->
        activationYield(activation.sourceClass, activation.alternative).forEach {
            available[it] = (available[it] ?: 0) + 1
        }
    }
    val required = (demand + activations.flatMap { it.costPayment }).groupingBy { it }.eachCount()
    return required.all { (type, count) -> count <= (available[type] ?: 0) }
}

/**
 * Whether *some* order runs [activations] without the pool ever going short (CR 601.2g), found by
 * trying **every permutation** — deliberately a different algorithm from the enumerator's memoized
 * subset DP, so the two can only agree by both being right (docs/design/mana-payment.md §11.2).
 *
 * Trivially true while no activation costs mana, which is every board before `FW-MANACOST`, so the
 * permutation walk never runs on them.
 */
private fun oracleOrderable(
    pool: Map<ManaType, Int>,
    activations: List<ManaActivation>,
): Boolean {
    if (activations.none { it.costPayment.isNotEmpty() }) return true

    fun walk(
        remaining: List<ManaActivation>,
        current: Map<ManaType, Int>,
    ): Boolean {
        if (remaining.isEmpty()) return true
        return remaining.indices.any { index ->
            val next = remaining[index]
            val afterCost = current.toMutableMap()
            var payable = true
            next.costPayment.forEach { unit ->
                val held = afterCost[unit] ?: 0
                if (held == 0) payable = false else afterCost[unit] = held - 1
            }
            if (!payable) {
                false
            } else {
                activationYield(next.sourceClass, next.alternative).forEach {
                    afterCost[it] = (afterCost[it] ?: 0) + 1
                }
                walk(remaining.filterIndexed { at, _ -> at != index }, afterCost)
            }
        }
    }
    return walk(activations, pool)
}

/**
 * Whether [activations] tap no more untapped creatures than [state]'s seat controls (CR 602.1) —
 * counted here by *naming the objects*, one per source a plan taps or sacrifices plus one per
 * "Tap an untapped creature you control" component, rather than by the enumerator's per-class prefix
 * arithmetic.
 */
private fun oracleCreatureBudgetHolds(
    state: GameState,
    activations: List<ManaActivation>,
): Boolean {
    if (activations.none { ManaAbilityCost.TapAnotherCreature in it.alternative.cost }) return true
    val budget = untappedCreatures(state, alice).size
    val drain =
        activations
            .groupBy { it.sourceClass }
            .entries
            .sumOf { (key, uses) ->
                val members =
                    state.sharedZones.battlefield.filter { it.owner == alice && sourceClassKeyOf(state, it) == key }
                uses.withIndex().sumOf { (useIndex, activation) ->
                    val member = members.getOrNull(useIndex)
                    val consumesSelf =
                        ManaAbilityCost.TapSelf in activation.alternative.cost ||
                            ManaAbilityCost.SacrificeSelf in activation.alternative.cost
                    val untappedCreature = member != null && !member.tapped && isCreature(state, member)
                    val self = if (consumesSelf && untappedCreature) 1 else 0
                    val helper = if (ManaAbilityCost.TapAnotherCreature in activation.alternative.cost) 1 else 0
                    self + helper
                }
            }
    return drain <= budget
}

/**
 * Whether every activation spends at least one of its mana, found by exhaustive assignment rather
 * than by the enumerator's Hall check or its Kuhn matching — the point of an oracle is to agree by a
 * different route.
 *
 * The sinks are the demanded mana **and** each activation's own mana cost, an activation being
 * forbidden from funding itself (docs/design/mana-payment.md §11.5). A sink is keyed by
 * `(owner, type)`, `owner` being the activation whose cost it is or `-1` for the cost being paid.
 */
private fun oracleSpendsEvery(
    activations: List<ManaActivation>,
    demand: List<ManaType>,
): Boolean {
    val remaining = mutableMapOf<Pair<Int, ManaType>, Int>()
    demand.forEach { type -> remaining.merge(-1 to type, 1, Int::plus) }
    activations.forEachIndexed { owner, activation ->
        activation.costPayment.forEach { type -> remaining.merge(owner to type, 1, Int::plus) }
    }

    fun assign(index: Int): Boolean {
        if (index == activations.size) return true
        val activation = activations[index]
        val yields = activationYield(activation.sourceClass, activation.alternative).distinct()
        return remaining.keys.toList().any { sink ->
            val left = remaining[sink] ?: 0
            if (sink.first == index || sink.second !in yields || left == 0) {
                false
            } else {
                remaining[sink] = left - 1
                val ok = assign(index + 1)
                remaining[sink] = left
                ok
            }
        }
    }
    return assign(0)
}
