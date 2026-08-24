package dev.mtgplay.rules

import dev.mtgplay.core.mana.Color
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.ManaActivation
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.decision.SymbolPayment
import dev.mtgplay.rules.engine.enumeratePaymentPlans
import dev.mtgplay.rules.engine.manaSourceClasses
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Payment-plan enumeration (CR 601.2g–h) against docs/design/mana-payment.md: equivalence
 * collapsing, every MVP cost shape, life-bound Phyrexian plans, the P8.3 multi-mana activation
 * (CR 605.1b) that pays two symbols off one tap, and — via a brute-force oracle and a
 * planner/executor correspondence property — enumeration completeness in both directions.
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
            plans.single().activations shouldContainExactly listOf(ManaActivation(mountainClass.key, ManaType.RED))
            plans.single().payments shouldContainExactly listOf(SymbolPayment.WithMana(ManaType.RED))
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
            // The life plan activates nothing: paying life needs no mana ability (CR 107.4).
            plans[1].activations.shouldBeEmpty()
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
            // Two Mountains, or a Mountain and the Forest; which symbol each pays is no longer a
            // distinction, so the cross-run permutation duplicate of the pre-P8.3 model is gone.
            plans shouldHaveSize 2
            plans.map { plan -> plan.activations.map { it.produced } } shouldBe
                listOf(listOf(ManaType.RED, ManaType.RED), listOf(ManaType.RED, ManaType.GREEN))
        }

        "an unaffordable cost enumerates no plans" {
            plansFor("{R}", SeatSetup(battlefield = listOf("Fixture Forest"))).shouldBeEmpty()
            plansFor("{2}", SeatSetup(battlefield = listOf("Fixture Mountain"))).shouldBeEmpty()
            plansFor("{R}", SeatSetup()).shouldBeEmpty()
        }

        "design note: a {0} cost enumerates exactly one plan — the empty one" {
            val plans = plansFor("{0}", SeatSetup(battlefield = listOf("Fixture Mountain")))
            plans shouldHaveSize 1
            plans.single() shouldBe PaymentPlan(emptyList(), emptyList())
        }

        "design note: paying from the pool and tapping anyway are distinct plans, the pooled one first" {
            val base = stateWith(SeatSetup(battlefield = listOf("Fixture Mountain")))
            val pooled = base.withPool(ManaType.RED)
            val plans = enumeratePaymentPlans(pooled, alice, ManaCost.parse("{R}"))
            // Spending the float leaves the Mountain untapped; tapping it leaves the float alive.
            // Both are legal and leave different states, so both are enumerated (ADR-005).
            plans shouldHaveSize 2
            plans[0].activations.shouldBeEmpty()
            plans[1].activations shouldHaveSize 1
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

        // ---- P8.3: one activation, several symbols (CR 605.1b) -----------------------------------

        "CR 605.1b: one activation of a ramp-enchanted Forest pays {1}{G} as a single plan" {
            // The defect this packet closes: before P8.3 the CR 605.1b bonus could not pay a symbol
            // of the very cost whose payment produced it, so this cost enumerated no plan at all.
            val state = rampState(chosen = Color.GREEN)
            val plans = enumeratePaymentPlans(state, alice, ManaCost.parse("{1}{G}"))
            plans shouldHaveSize 1
            val plan = plans.single()
            plan.activations shouldHaveSize 1
            plan.activations
                .single()
                .sourceClass.bonus shouldContainExactly listOf(ManaType.GREEN)
            plan.payments shouldContainExactly
                listOf(SymbolPayment.WithMana(ManaType.GREEN), SymbolPayment.WithMana(ManaType.GREEN))
        }

        "CR 500.4: an activation whose bonus goes unspent is still legal — the surplus simply floats" {
            val state = rampState(chosen = Color.GREEN)
            val plans = enumeratePaymentPlans(state, alice, ManaCost.parse("{G}"))
            plans shouldHaveSize 1
            plans.single().activations shouldHaveSize 1
            // One activation, one symbol paid: the additional {G} is produced and left floating.
            plans.single().payments shouldHaveSize 1
        }

        "CR 605.1b: the bonus mana alone may pay a symbol its source's own mana cannot" {
            // The Aura chose RED on a Forest, so one activation yields {G} and {R}; a {R} cost is
            // payable even though nothing on the battlefield taps for red.
            val state = rampState(chosen = Color.RED)
            val plans = enumeratePaymentPlans(state, alice, ManaCost.parse("{R}"))
            plans shouldHaveSize 1
            plans
                .single()
                .activations
                .single()
                .produced shouldBe ManaType.GREEN
            plans.single().payments shouldContainExactly listOf(SymbolPayment.WithMana(ManaType.RED))
        }

        "docs/design/mana-payment.md §4: no plan activates a source none of whose mana it spends" {
            // Two ramp Forests and a {G} cost: one activation covers it, and no plan taps both.
            val state = rampState(chosen = Color.GREEN, ramps = 2)
            val plans = enumeratePaymentPlans(state, alice, ManaCost.parse("{G}"))
            plans.forEach { it.activations shouldHaveSize 1 }
        }

        // ---- completeness -------------------------------------------------------------------------

        "oracle: enumeration is complete and duplicate-free for every MVP cost shape" {
            oracleScenarios().forEach { (label, scenario) ->
                val (state, cost) = scenario
                val enumerated = enumeratePaymentPlans(state, alice, cost)
                val canonical = enumerated.map { canonicalForm(cost, it) }
                withClue(label) {
                    // No duplicates survive collapsing…
                    canonical.toSet() shouldHaveSize enumerated.size
                    // …and the set equals the naive oracle's: complete in both directions.
                    canonical.toSet() shouldBe oraclePlans(state, cost)
                }
            }
        }

        "CR 601.2g–h: every enumerated plan executes, and lands exactly the pool it declared" {
            oracleScenarios().forEach { (label, scenario) ->
                val (state, cost) = scenario
                val plans = enumeratePaymentPlans(state, alice, cost)
                plans.forEach { plan ->
                    withClue("$label / $plan") { assertExecutesAsDeclared(state, cost, plan) }
                }
            }
        }

        "ADR-006: enumeration is a pure function of the state — equal states enumerate equal lists" {
            oracleScenarios().forEach { (label, scenario) ->
                val (state, cost) = scenario
                withClue(label) {
                    enumeratePaymentPlans(state, alice, cost) shouldBe enumeratePaymentPlans(state, alice, cost)
                }
            }
        }

        "docs/design/mana-payment.md §4: the activation count never exceeds the mana the plan spends" {
            oracleScenarios().forEach { (label, scenario) ->
                val (state, cost) = scenario
                enumeratePaymentPlans(state, alice, cost).forEach { plan ->
                    val manaPayments = plan.payments.count { it is SymbolPayment.WithMana }
                    withClue("$label / $plan") { (plan.activations.size <= manaPayments) shouldBe true }
                }
            }
        }

        "the ramp scenarios genuinely exercise multi-mana activations" {
            // Guards the oracle suite: a scenario set with no two-mana activation would pass
            // vacuously and prove nothing about the P8.3 reshape.
            oracleScenarios()
                .flatMap { (_, scenario) -> enumeratePaymentPlans(scenario.first, alice, scenario.second) }
                .count { plan -> plan.activations.any { it.sourceClass.bonus.isNotEmpty() } }
                .shouldBeGreaterThan(0)
        }
    })
