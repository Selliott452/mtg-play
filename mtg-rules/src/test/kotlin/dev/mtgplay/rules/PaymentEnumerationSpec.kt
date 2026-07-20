package dev.mtgplay.rules

import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.ManaSourceChoice
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.decision.SourceClassKey
import dev.mtgplay.rules.decision.SymbolPayment
import dev.mtgplay.rules.engine.enumeratePaymentPlans
import dev.mtgplay.rules.engine.expandToUnits
import dev.mtgplay.rules.engine.manaSourceClasses
import dev.mtgplay.rules.engine.paymentSatisfies
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf

/**
 * Payment-plan enumeration (CR 601.2g–h) against docs/design/mana-payment.md: equivalence
 * collapsing, every MVP cost shape, life-bound Phyrexian plans, and — via a brute-force
 * oracle — enumeration completeness in both directions.
 */
class PaymentEnumerationSpec :
    StringSpec({

        fun stateWith(setup: SeatSetup): GameState = fixtureState(aliceSetup = setup, bobSetup = SeatSetup())

        fun plansFor(
            cost: String,
            setup: SeatSetup,
        ): List<PaymentPlan> = enumeratePaymentPlans(stateWith(setup), alice, ManaCost.parse(cost))

        "design note: five equivalent untapped sources and a one-mana cost collapse to exactly one plan" {
            val state = stateWith(SeatSetup(battlefield = List(5) { "Fixture Mountain" }))
            val plans = enumeratePaymentPlans(state, alice, ManaCost.parse("{R}"))
            plans shouldHaveSize 1
            val mountainClass = manaSourceClasses(state, alice).single()
            mountainClass.members shouldHaveSize 5
            plans.single().payments.single() shouldBe
                SymbolPayment.WithMana(ManaType.RED, ManaSourceChoice.ByTapping(mountainClass.key))
        }

        "CR 107.4: a hybrid {G/U} cost with both colors available enumerates exactly two plans" {
            val plans = plansFor("{G/U}", SeatSetup(battlefield = listOf("Fixture Forest", "Fixture Island")))
            plans shouldHaveSize 2
            val paidTypes =
                plans.map {
                    it.payments
                        .single()
                        .shouldBeInstanceOf<SymbolPayment.WithMana>()
                        .mana
                }
            // WUBRG candidate order (CR 105.1): the blue side enumerates before the green side.
            paidTypes shouldBe listOf(ManaType.BLUE, ManaType.GREEN)
        }

        "CR 107.4: a Phyrexian {R/P} cost with a red source enumerates the mana plan and the 2-life plan" {
            val plans = plansFor("{R/P}", SeatSetup(battlefield = listOf("Fixture Mountain")))
            plans shouldHaveSize 2
            plans[0]
                .payments
                .single()
                .shouldBeInstanceOf<SymbolPayment.WithMana>()
                .mana shouldBe ManaType.RED
            plans[1].payments.single() shouldBe SymbolPayment.WithTwoLife
        }

        "CR 118.8: the 2-life plan is enumerated at exactly 2 life but not at 1" {
            val atTwo = plansFor("{R/P}", SeatSetup(life = 2))
            atTwo shouldHaveSize 1
            atTwo.single().payments.single() shouldBe SymbolPayment.WithTwoLife
            plansFor("{R/P}", SeatSetup(life = 1)).shouldBeEmpty()
        }

        "CR 107.4c: {C} demands specifically colorless mana — a colorless source pays it, a colored one cannot" {
            plansFor("{C}", SeatSetup(battlefield = listOf("Fixture Wastes"))) shouldHaveSize 1
            plansFor("{C}", SeatSetup(battlefield = listOf("Fixture Mountain"))).shouldBeEmpty()
        }

        "CR 107.4d: generic {1} accepts any mana type, including colorless" {
            plansFor("{1}", SeatSetup(battlefield = listOf("Fixture Wastes"))) shouldHaveSize 1
            plansFor("{1}", SeatSetup(battlefield = listOf("Fixture Mountain"))) shouldHaveSize 1
        }

        "design note: an any-color source contributes one plan per chosen color" {
            plansFor("{1}", SeatSetup(battlefield = listOf("Fixture Prism"))) shouldHaveSize 5
            plansFor("{G}", SeatSetup(battlefield = listOf("Fixture Prism"))) shouldHaveSize 1
        }

        "design note: {1}{R} over two Mountains and a Forest collapses permutations to two plans" {
            val plans =
                plansFor(
                    "{1}{R}",
                    SeatSetup(battlefield = listOf("Fixture Mountain", "Fixture Mountain", "Fixture Forest")),
                )
            // The {R} unit must tap a Mountain; the generic unit taps a Mountain or the Forest.
            plans shouldHaveSize 2
        }

        "an unaffordable cost enumerates no plans" {
            plansFor("{R}", SeatSetup(battlefield = listOf("Fixture Forest"))).shouldBeEmpty()
            plansFor("{2}", SeatSetup(battlefield = listOf("Fixture Mountain"))).shouldBeEmpty()
            plansFor("{R}", SeatSetup()).shouldBeEmpty()
        }

        "design note: pooled mana enumerates ahead of tapping and is a distinct plan" {
            val base = stateWith(SeatSetup(battlefield = listOf("Fixture Mountain")))
            val pooled =
                base.copy(
                    players =
                        base.players.putting(
                            alice,
                            base.players.getValue(alice).copy(manaPool = persistentListOf(ManaType.RED)),
                        ),
                )
            val plans = enumeratePaymentPlans(pooled, alice, ManaCost.parse("{R}"))
            plans shouldHaveSize 2
            plans[0]
                .payments
                .single()
                .shouldBeInstanceOf<SymbolPayment.WithMana>()
                .source shouldBe
                ManaSourceChoice.FromPool
        }

        "a tapped source is no payment source" {
            val base = stateWith(SeatSetup(battlefield = listOf("Fixture Mountain")))
            val tappedMountain = base.sharedZones.battlefield[0].copy(tapped = true)
            val tapped =
                base.copy(
                    sharedZones =
                        base.sharedZones.copy(
                            battlefield =
                                base.sharedZones.battlefield
                                    .removingAt(0)
                                    .addingAt(0, tappedMountain),
                        ),
                )
            enumeratePaymentPlans(tapped, alice, ManaCost.parse("{R}")).shouldBeEmpty()
        }

        "oracle: enumeration is complete and duplicate-free for every MVP cost shape" {
            val scenarios =
                listOf(
                    "{R}" to SeatSetup(battlefield = List(3) { "Fixture Mountain" }),
                    "{1}{R}" to
                        SeatSetup(battlefield = listOf("Fixture Mountain", "Fixture Mountain", "Fixture Forest")),
                    "{2}" to SeatSetup(battlefield = listOf("Fixture Mountain", "Fixture Forest", "Fixture Prism")),
                    "{G/U}" to
                        SeatSetup(battlefield = listOf("Fixture Forest", "Fixture Island", "Fixture Prism")),
                    "{R/P}{R/P}" to SeatSetup(battlefield = listOf("Fixture Mountain"), life = 3),
                    "{C}{1}" to SeatSetup(battlefield = listOf("Fixture Wastes", "Fixture Mountain")),
                    "{0}" to SeatSetup(battlefield = listOf("Fixture Mountain")),
                )
            scenarios.forEach { (cost, setup) ->
                val state = stateWith(setup)
                val parsed = ManaCost.parse(cost)
                val enumerated = enumeratePaymentPlans(state, alice, parsed)
                val canonical = enumerated.map { canonicalForm(parsed, it) }
                // No duplicates survive collapsing…
                canonical.toSet() shouldHaveSize enumerated.size
                // …and the set equals the naive oracle's: complete in both directions.
                canonical.toSet() shouldBe oraclePlans(state, parsed)
            }
        }
    })

/**
 * The brute-force oracle of docs/design/mana-payment.md: naively generate every raw assignment
 * of a payable payment per expanded symbol, keep the resource-feasible ones (source-class
 * capacity, CR 118.8 life bound; the pool is empty in the oracle scenarios), and canonicalize.
 * The enumerator must produce exactly this set.
 */
private fun oraclePlans(
    state: GameState,
    cost: ManaCost,
): Set<Map<Int, Map<SymbolPayment, Int>>> {
    val units = expandToUnits(cost)
    val classes = manaSourceClasses(state, alice)
    val rawCandidates: List<List<SymbolPayment>> =
        units.map { symbol ->
            buildList {
                for (type in ManaType.entries) {
                    for (sourceClass in classes) {
                        if (type in sourceClass.key.profile) {
                            add(SymbolPayment.WithMana(type, ManaSourceChoice.ByTapping(sourceClass.key)))
                        }
                    }
                }
                add(SymbolPayment.WithTwoLife)
            }.filter { paymentSatisfies(symbol, it) }
        }
    val capacities = classes.associate { it.key to it.members.size }
    val life = state.players.getValue(alice).life
    return cartesianProduct(rawCandidates)
        .filter { feasible(it, capacities, life) }
        .map { canonicalForm(cost, PaymentPlan(it)) }
        .toSet()
}

private fun cartesianProduct(candidates: List<List<SymbolPayment>>): List<List<SymbolPayment>> =
    candidates.fold(listOf(emptyList())) { acc, options ->
        acc.flatMap { prefix -> options.map { prefix + it } }
    }

private fun feasible(
    assignment: List<SymbolPayment>,
    classCapacity: Map<SourceClassKey, Int>,
    life: Int,
): Boolean {
    val taps = mutableMapOf<SourceClassKey, Int>()
    var lifePaid = 0
    for (payment in assignment) {
        when (payment) {
            is SymbolPayment.WithMana ->
                when (val source = payment.source) {
                    ManaSourceChoice.FromPool -> return false
                    is ManaSourceChoice.ByTapping -> taps.merge(source.sourceClass, 1, Int::plus)
                }
            SymbolPayment.WithTwoLife -> lifePaid += 2
        }
    }
    return lifePaid <= life && taps.all { (key, count) -> count <= (classCapacity[key] ?: 0) }
}

/**
 * A plan's canonical form: per run of identical expanded symbols, the multiset of payments —
 * exactly the equivalence the design note's non-decreasing rule collapses by.
 */
private fun canonicalForm(
    cost: ManaCost,
    plan: PaymentPlan,
): Map<Int, Map<SymbolPayment, Int>> {
    val units = expandToUnits(cost)
    val runIndex = IntArray(units.size)
    var run = 0
    units.forEachIndexed { index, symbol ->
        if (index > 0 && symbol != units[index - 1]) run += 1
        runIndex[index] = run
    }
    return plan.payments
        .withIndex()
        .groupBy({ runIndex[it.index] }, { it.value })
        .mapValues { (_, payments) -> payments.groupingBy { it }.eachCount() }
}
