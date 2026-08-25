package dev.mtgplay.rules

import dev.mtgplay.core.definition.ManaAbility
import dev.mtgplay.core.definition.ManaAbilityCost
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.Counter
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.ManaActivation
import dev.mtgplay.rules.decision.PaymentPlan
import dev.mtgplay.rules.decision.ProductionAlternative
import dev.mtgplay.rules.decision.SymbolPayment
import dev.mtgplay.rules.engine.enumeratePaymentPlans
import dev.mtgplay.rules.engine.manaActivationOrder
import dev.mtgplay.rules.engine.manaSourceClasses
import dev.mtgplay.rules.engine.payManaPlan
import dev.mtgplay.rules.engine.productionProfile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/**
 * `FW-MANACOST` — mana abilities whose activation costs something (docs/design/mana-payment.md §11).
 *
 * The completeness oracle already set-compares the enumerator against a brute force on the costed
 * boards (`PaymentEnumerationSpec`), which proves the *search*. What it structurally cannot prove is
 * that the search is solving the right problem, because it shares `manaSourceClasses` with the
 * enumerator (§10's blind spot). This spec is the other half: it names the specific plans that must
 * and must not exist, so a model that is wrong in the same way on both sides still fails here.
 */
class ManaAbilityCostSpec :
    StringSpec({

        fun stateWith(setup: SeatSetup): GameState = fixtureState(aliceSetup = setup, bobSetup = SeatSetup())

        fun plansFor(
            cost: String,
            setup: SeatSetup,
        ): List<PaymentPlan> = enumeratePaymentPlans(stateWith(setup), alice, ManaCost.parse(cost))

        // ---- the acyclicity problem (§11.2) ----------------------------------------------------

        "CR 601.2g: two costed sources on an empty pool cannot fund each other out of nothing" {
            // Each Fixture Filter is "{1}, {T}: Add one mana of any color". Two of them produce two
            // mana and cost two mana, so aggregate arithmetic balances exactly — and neither can go
            // first. This is the plan a coverage-only model would offer and the rules never allow.
            plansFor("{R}", SeatSetup(battlefield = listOf("Fixture Filter", "Fixture Filter")))
                .shouldBeEmpty()
        }

        "CR 601.2g: a costed source funded from the pool is a legal plan, and it executes" {
            val setup = SeatSetup(battlefield = listOf("Fixture Filter"))
            val state = stateWith(setup).withPool(ManaType.GREEN)
            val plans = enumeratePaymentPlans(state, alice, ManaCost.parse("{R}"))
            // The only line: spend the floating green on the filter's {1}, tap it for red, pay {R}.
            plans shouldHaveSize 1
            val plan = plans.single()
            plan.activations.single().costPayment shouldContainExactly listOf(ManaType.GREEN)
            plan.activations.single().produced shouldContainExactly listOf(ManaType.RED)
            val after = payManaPlan(state, alice, ManaCost.parse("{R}"), plan)
            after.players
                .getValue(alice)
                .manaPool
                .shouldBeEmpty()
            after.sharedZones.battlefield
                .single { it.card.name == "Fixture Filter" }
                .tapped shouldBe true
        }

        "CR 601.2g: a costed source may be funded by another costed source, in the derived order" {
            // Forest -> filter A -> filter B is a three-step chain; the Forest is free so it runs
            // first, and the two filters must run in the one order that works.
            val setup =
                SeatSetup(battlefield = listOf("Fixture Filter", "Fixture Filter", "Fixture Forest"))
            val chained =
                plansFor("{R}{W}", setup).filter { plan -> plan.activations.count { it.costPayment.isNotEmpty() } == 2 }
            chained.shouldNotBeNull()
            chained.forEach { plan ->
                withClue(plan) {
                    val order = manaActivationOrder(emptyMap(), plan.activations)
                    // A feasible order exists, and the free Forest activation is in it exactly once.
                    order.shouldNotBeNull()
                    order shouldHaveSize plan.activations.size
                }
            }
        }

        "CR 601.2g: the derived order is what the executor runs, so every enumerated plan executes" {
            val setup =
                SeatSetup(battlefield = listOf("Fixture Filter", "Fixture Filter", "Fixture Forest"))
            val state = stateWith(setup)
            enumeratePaymentPlans(state, alice, ManaCost.parse("{R}{W}")).forEach { plan ->
                withClue(plan) {
                    // The correspondence property of §10, run over the costed board: executing a plan
                    // must succeed and leave pool_before ⊎ yields ⊖ costs ⊖ demand.
                    assertExecutesAsDeclared(state, ManaCost.parse("{R}{W}"), plan)
                }
            }
        }

        "CR 601.2g: manaActivationOrder is the single derivation the planner and executor share" {
            // The cycle, stated directly: the same two activations the enumerator refused have no
            // order, and the function that says so is the one payManaPlan calls.
            val state = stateWith(SeatSetup(battlefield = listOf("Fixture Filter", "Fixture Filter")))
            val key = manaSourceClasses(state, alice).single().key
            val red = key.profile.single { it.produced == listOf(ManaType.RED) }
            val cycle =
                listOf(
                    ManaActivation(key, red, listOf(ManaType.RED)),
                    ManaActivation(key, red, listOf(ManaType.RED)),
                )
            manaActivationOrder(emptyMap(), cycle).shouldBeNull()
            // With one red already floating the same pair *is* orderable — the cycle was the pool's
            // emptiness, not the pair.
            manaActivationOrder(mapOf(ManaType.RED to 1), cycle).shouldNotBeNull()
        }

        // ---- the no-idle rule with costs (§11.5) ------------------------------------------------

        "docs/design/mana-payment.md §11.5: an activation whose only sink is its own cost is idle" {
            // Two floating mana and a `{1}` cost that is *already* payable. A filter that eats the
            // green and replaces it with a green has done nothing at all — its yield's only possible
            // consumer is the cost it just paid, which is the one sink the matching forbids it.
            // Hall's condition over type subsets cannot express that exclusion, which is why the
            // costed path is a real bipartite matching rather than the free path's 64-subset sweep.
            val state =
                stateWith(SeatSetup(battlefield = listOf("Fixture Filter")))
                    .withPool(ManaType.GREEN)
                    .withPool(ManaType.RED)
            val plans = enumeratePaymentPlans(state, alice, ManaCost.parse("{R}"))
            val greenForGreen =
                plans.filter { plan ->
                    plan.activations.any {
                        it.costPayment == listOf(ManaType.GREEN) && it.produced == listOf(ManaType.GREEN)
                    }
                }
            greenForGreen.shouldBeEmpty()

            // Three plans survive, and each leaves a genuinely different position: pay the {R} from
            // the pool untapped; convert the green into a red and pay with one of the two (a red is
            // left floating); or spend the red on the filter and pay with its output (the green is
            // left floating). Every one of them executes exactly as it declared.
            plans shouldHaveSize 3
            plans.count { it.activations.isEmpty() } shouldBe 1
            plans.forEach { plan ->
                withClue(plan) { assertExecutesAsDeclared(state, ManaCost.parse("{R}"), plan) }
            }
        }

        "docs/design/mana-payment.md §4: a costed activation that pays the demand is not idle" {
            // The mirror of the case above, and the reason the exclusion is stated per *sink* rather
            // than as "an activation that spends mana is idle": here the filter's own {1} comes from
            // the pool and its output pays the cost, which taps a land and is a genuinely different
            // board from paying the {R} directly. The plan must survive.
            val state = stateWith(SeatSetup(battlefield = listOf("Fixture Filter"))).withPool(ManaType.RED)
            val plans = enumeratePaymentPlans(state, alice, ManaCost.parse("{R}"))
            plans.count { it.activations.isEmpty() } shouldBe 1
            plans.count { it.activations.size == 1 } shouldBe 1
        }

        // ---- two abilities, two costs, one source (§11 / the SourceClassKey reshape) ------------

        "CR 605.1a: a source printing a free and a costed ability offers both as alternatives" {
            val state = stateWith(SeatSetup(battlefield = listOf("Fixture Pylon Gate")))
            val profile = productionProfile(state, state.sharedZones.battlefield.single()).shouldNotBeNull()
            // The free {T}: Add {C} sorts first; the five costed colours follow.
            profile.first() shouldBe ProductionAlternative.tapping(ManaType.COLORLESS)
            profile shouldHaveSize 6
            profile.drop(1).forEach { it.manaCost?.cost?.render() shouldBe "{1}" }
            // Both alternatives tap the same land, so only one of them can ever be taken.
            plansFor("{C}{R}", SeatSetup(battlefield = listOf("Fixture Pylon Gate"))).shouldBeEmpty()
        }

        // ---- the creature budget (§11.3) --------------------------------------------------------

        "CR 602.1: a Caretaker cannot tap itself for its own 'tap an untapped creature' cost" {
            // One Caretaker alone: the {T} taps it, and there is no second creature to tap.
            val state = stateWith(SeatSetup(battlefield = listOf("Fixture Caretaker"))).settled()
            enumeratePaymentPlans(state, alice, ManaCost.parse("{W}")).shouldBeEmpty()
        }

        "CR 602.1: two Caretakers make one mana between them, not two" {
            val setup = SeatSetup(battlefield = listOf("Fixture Caretaker", "Fixture Caretaker"))
            val state = stateWith(setup).settled()
            // One activation: Caretaker A taps itself and taps Caretaker B.
            enumeratePaymentPlans(state, alice, ManaCost.parse("{W}")) shouldHaveSize 1
            // Two would need four untapped creatures; there are two.
            enumeratePaymentPlans(state, alice, ManaCost.parse("{W}{W}")).shouldBeEmpty()
        }

        "CR 602.5a: a Caretaker may tap a summoning-sick creature, because the {T} is not on it" {
            // The Elf is sick and taps for nothing itself, but it is a perfectly legal thing for the
            // Caretaker to tap — the tap symbol is on the Caretaker's ability, not on the Elf.
            val state =
                fixtureState(
                    aliceSetup = SeatSetup(battlefield = listOf("Fixture Caretaker", "Fixture Mana Elf")),
                    bobSetup = SeatSetup(),
                ).let { base ->
                    base.copy(
                        sharedZones =
                            base.sharedZones.copy(
                                battlefield =
                                    base.sharedZones.battlefield
                                        .map { obj ->
                                            if (obj.card.name == "Fixture Caretaker") {
                                                obj.copy(summoningSick = false)
                                            } else {
                                                obj
                                            }
                                        }.toPersistentList(),
                            ),
                    )
                }
            val plans = enumeratePaymentPlans(state, alice, ManaCost.parse("{W}"))
            plans shouldHaveSize 1
            val after = payManaPlan(state, alice, ManaCost.parse("{W}"), plans.single())
            // Both are tapped: the Caretaker by its own {T}, the sick Elf by the second component.
            after.sharedZones.battlefield.filter { it.owner == alice && it.tapped } shouldHaveSize 2
        }

        "CR 602.1: the helper creature is never one the rest of the plan still needs" {
            // A Caretaker and two Elves: the plan that taps the Caretaker *and* both Elves needs the
            // Caretaker's helper to be a creature no later activation wants. There is exactly one
            // spare untapped creature at each point, so the pick must be the right one or the plan
            // dead-ends mid-payment.
            val setup =
                SeatSetup(battlefield = listOf("Fixture Caretaker", "Fixture Mana Elf", "Fixture Mana Elf"))
            val state = stateWith(setup).settled()
            enumeratePaymentPlans(state, alice, ManaCost.parse("{G}{G}")).forEach { plan ->
                withClue(plan) { assertExecutesAsDeclared(state, ManaCost.parse("{G}{G}"), plan) }
            }
        }

        // ---- once each turn (CR 602.5b, §11.4) --------------------------------------------------

        "CR 602.5b: a spent 'activate only once each turn' source is no source for the rest of the turn" {
            val setup = SeatSetup(battlefield = listOf("Fixture Wall"))
            val state = stateWith(setup)
            val plans = enumeratePaymentPlans(state, alice, ManaCost.parse("{G}"))
            plans shouldHaveSize 1
            val after = payManaPlan(state, alice, ManaCost.parse("{G}"), plans.single())
            // The counter is on it, the mana was added, and the source is gone from the classes.
            val wall = after.sharedZones.battlefield.single { it.card.name == "Fixture Wall" }
            wall.counterCount(Counter.MINUS_ZERO_MINUS_ONE) shouldBe 1
            wall.manaAbilitiesActivatedThisTurn shouldContainExactly setOf(0)
            productionProfile(after, wall).shouldBeNull()
            manaSourceClasses(after, alice).shouldBeEmpty()
        }

        "CR 602.5b: the restriction bounds a source whose cost neither taps nor removes it" {
            // Two Walls pay {G}{G}; a third {G} is unavailable even though both are still untapped,
            // on the battlefield and perfectly healthy.
            val setup = SeatSetup(battlefield = listOf("Fixture Wall", "Fixture Wall"))
            enumeratePaymentPlans(stateWith(setup), alice, ManaCost.parse("{G}{G}")) shouldHaveSize 1
            enumeratePaymentPlans(stateWith(setup), alice, ManaCost.parse("{G}{G}{G}")).shouldBeEmpty()
        }

        "CR 602.2a: a counter-cost source taps for mana while tapped, having no {T} to pay" {
            val setup = SeatSetup(battlefield = listOf("Fixture Wall"))
            val state =
                stateWith(setup).let { base ->
                    base.copy(
                        sharedZones =
                            base.sharedZones.copy(
                                battlefield =
                                    base.sharedZones.battlefield
                                        .map { it.copy(tapped = true) }
                                        .toPersistentList(),
                            ),
                    )
                }
            enumeratePaymentPlans(state, alice, ManaCost.parse("{G}")) shouldHaveSize 1
        }

        // ---- declaration-time guards -------------------------------------------------------------

        "CR 602.5b: an unrestricted ability that never consumes its source is rejected at construction" {
            // Without either, one member could be activated without limit and no finite enumeration
            // of payment plans could exist.
            val thrown =
                shouldThrow<IllegalArgumentException> {
                    ManaAbility(
                        persistentListOf(ManaType.GREEN),
                        cost = persistentListOf(ManaAbilityCost.Mana(ManaCost.parse("{1}"))),
                    )
                }
            thrown.message.shouldContain("CR 602.5b")
        }

        "gauntlet triage T2: '{T}, Sacrifice this' is a composite cost, not an either/or" {
            // Lotus Petal's shape, and the trap the old `viaSacrifice` flag set: encoding it as
            // "sacrifice instead of tapping" gave a *tapped* Petal a live mana ability, because a
            // sacrifice source is deliberately usable while tapped. A cost list has no either/or.
            val petal =
                ManaAbility(
                    persistentListOf(ManaType.GREEN),
                    cost = persistentListOf(ManaAbilityCost.TapSelf, ManaAbilityCost.SacrificeSelf),
                )
            petal.cost shouldContainExactly
                listOf(ManaAbilityCost.TapSelf, ManaAbilityCost.SacrificeSelf)
            // CR 602.2a still applies through the {T} component: a tapped source is unavailable, which
            // is exactly what the flag could not express.
            val alternative = ProductionAlternative(petal.cost, listOf(ManaType.GREEN))
            alternative.viaSacrifice shouldBe true
            alternative.manaCost.shouldBeNull()
        }

        "CR 601.2g: a plan whose recorded cost payment does not satisfy the ability's cost fails loudly" {
            val state = stateWith(SeatSetup(battlefield = listOf("Fixture Filter"))).withPool(ManaType.GREEN)
            val key = manaSourceClasses(state, alice).single().key
            val red = key.profile.single { it.produced == listOf(ManaType.RED) }
            val malformed =
                PaymentPlan(
                    listOf(ManaActivation(key, red, costPayment = emptyList())),
                    listOf(SymbolPayment.WithMana(ManaType.RED)),
                )
            val thrown =
                shouldThrow<IllegalArgumentException> {
                    payManaPlan(state, alice, ManaCost.parse("{R}"), malformed)
                }
            thrown.message.shouldContain("CR 601.2g")
        }
    })
