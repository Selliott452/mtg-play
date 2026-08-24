package dev.mtgplay.rules

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.engine.enumeratePaymentPlans
import dev.mtgplay.rules.engine.manaSourceClasses
import dev.mtgplay.rules.engine.payManaPlan
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.toPersistentList

/**
 * CR 302.6 on the mana-payment path: a creature's mana ability has `{T}` in its cost
 * (CR 605.1a), so it cannot be activated while the creature is summoning sick — the gate
 * `ManaSourceUsability.manaSourceUsable` enforces for **both** halves of payment.
 *
 * This is the `P-MANASICK` regression suite (docs/gauntlet-card-triage.md §7 T1). Before the
 * first creature mana source existed the clause was unreachable, and its absence would have been
 * silent and in the agent's favour: mana offered in the enumerated action space (ADR-005) that
 * the rules do not permit. Both halves are covered deliberately, because a planner and an
 * executor that disagree about a class's membership is the worst defect the payment model can
 * have (docs/design/mana-payment.md §10).
 */
class ManaSourceSummoningSicknessSpec :
    StringSpec({

        /**
         * A board of alice's named permanents, each summoning sick or not, in the order given,
         * with [hand] in her hand. Battlefield ids run from 0 in the order listed.
         */
        fun board(
            vararg permanents: Pair<String, Boolean>,
            hand: List<String> = emptyList(),
        ): GameState {
            val base =
                fixtureState(
                    aliceSetup = SeatSetup(hand = hand, battlefield = permanents.map { it.first }),
                    bobSetup = SeatSetup(),
                )
            val battlefield =
                base.sharedZones.battlefield.mapIndexed { index, obj ->
                    if (index < permanents.size) obj.copy(summoningSick = permanents[index].second) else obj
                }
            return base.copy(sharedZones = base.sharedZones.copy(battlefield = battlefield.toPersistentList()))
        }

        "CR 302.6: a summoning-sick creature mana source is in no source class and funds no plan" {
            val state = board("Fixture Mana Elf" to true)
            manaSourceClasses(state, alice).shouldBeEmpty()
            enumeratePaymentPlans(state, alice, ManaCost.parse("{G}")).shouldBeEmpty()
        }

        "CR 302.6: the same creature mana source funds a plan once it is no longer summoning sick" {
            val state = board("Fixture Mana Elf" to false)
            manaSourceClasses(state, alice).single().members shouldHaveSize 1
            val plans = enumeratePaymentPlans(state, alice, ManaCost.parse("{G}"))
            plans shouldHaveSize 1
            plans
                .single()
                .activations
                .single()
                .produced shouldBe listOf(ManaType.GREEN)
        }

        "CR 302.6: sickness gates the sick source only, not the rest of the battlefield" {
            val state = board("Fixture Mana Elf" to true, "Fixture Forest" to false)
            // The Forest still pays a single {G}…
            enumeratePaymentPlans(state, alice, ManaCost.parse("{G}")) shouldHaveSize 1
            // …but the sick Elf adds nothing, so a second {G} is unpayable.
            enumeratePaymentPlans(state, alice, ManaCost.parse("{G}{G}")).shouldBeEmpty()
        }

        "CR 302.6: a class's capacity counts only its unsick members" {
            val state = board("Fixture Mana Elf" to true, "Fixture Mana Elf" to false)
            // Two payment-equivalent Elves, one usable: capacity 1, so {G}{G} is unpayable.
            manaSourceClasses(state, alice).single().members shouldContainExactly listOf(ObjectId(1))
            enumeratePaymentPlans(state, alice, ManaCost.parse("{G}")) shouldHaveSize 1
            enumeratePaymentPlans(state, alice, ManaCost.parse("{G}{G}")).shouldBeEmpty()
        }

        "CR 601.2g: the executor activates the unsick member the planner counted, not the first one" {
            // The sick Elf is *first* in battlefield order, so an executor that re-derives its own
            // membership without the CR 302.6 gate taps the wrong — and illegal — permanent.
            val state = board("Fixture Mana Elf" to true, "Fixture Mana Elf" to false)
            val cost = ManaCost.parse("{G}")
            val plan = enumeratePaymentPlans(state, alice, cost).single()
            val after = payManaPlan(state, alice, cost, plan)
            after.sharedZones.battlefield
                .single { it.id == ObjectId(0) }
                .tapped shouldBe false
            after.sharedZones.battlefield
                .single { it.id == ObjectId(1) }
                .tapped shouldBe true
        }

        "CR 302.6: the restriction is on {T}, so a summoning-sick sacrifice source still produces mana" {
            // "Sacrifice this creature: Add {C}" has no {T} in its cost (CR 605.1a), and CR 302.6
            // restricts only {T} and {Q} abilities — the counterexample that keeps the gate narrow.
            val state = board("Fixture Mana Spawn" to true)
            manaSourceClasses(state, alice).single().members shouldHaveSize 1
            enumeratePaymentPlans(state, alice, ManaCost.parse("{C}")) shouldHaveSize 1
        }

        "CR 302.6: a non-creature mana source is never gated by summoning sickness" {
            // A land played this turn taps for mana at once; the CR 302.6 clause is about creatures.
            val state = board("Fixture Forest" to true)
            manaSourceClasses(state, alice).single().members shouldHaveSize 1
            enumeratePaymentPlans(state, alice, ManaCost.parse("{G}")) shouldHaveSize 1
        }

        "ADR-005: a cast payable only off a summoning-sick creature source is not enumerated" {
            val sick = board("Fixture Mana Elf" to true, hand = listOf("Fixture Bloom"))
            enumeratedCasts(pausedRequestOf<DecisionRequest.ChooseAction>(sick)) shouldNotContain "Fixture Bloom"

            val settled = board("Fixture Mana Elf" to false, hand = listOf("Fixture Bloom"))
            enumeratedCasts(pausedRequestOf<DecisionRequest.ChooseAction>(settled)) shouldContain "Fixture Bloom"
        }
    })
