package dev.mtgplay.rules

import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.Counter
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
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList

/**
 * CR 702.10c on the mana-payment path: haste lifts the CR 302.6 restriction on a creature's `{T}`
 * abilities, and a mana ability is an activated ability (CR 605.1a), so a hasty creature mana source
 * taps for mana the turn it arrives.
 *
 * The sibling of [ManaSourceSummoningSicknessSpec], and it exists for the same reason at one remove.
 * That suite pinned CR 302.6 on **both** halves of payment after the gap was found to exist in both.
 * `manaSourceUsable` is the shared predicate the payment planner ([manaSourceClasses]) and the
 * payment executor ([payManaPlan] → `resolveTapForMana`) each call, precisely so the two can never
 * disagree about a source class's membership (docs/design/mana-payment.md §10). Honouring haste
 * there honours it at both sites at once — and this suite asserts *both* rather than trusting the
 * sharing, because "the planner offered a source the executor then refuses to tap" is the failure
 * mode the file was created to prevent, and a keyword that lifts the gate is exactly the kind of
 * change that could reintroduce it.
 */
class HasteManaSourceSpec :
    StringSpec({

        /** Alice's named permanents in order, each summoning sick or not, with [hand] in her hand. */
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

        "CR 702.10c: the PLANNER admits a summoning-sick creature mana source that has haste" {
            val state = board("Fixture Hasty Elf" to true)
            manaSourceClasses(state, alice).single().members shouldContainExactly listOf(ObjectId(0))
            val plans = enumeratePaymentPlans(state, alice, ManaCost.parse("{G}"))
            plans shouldHaveSize 1
            plans
                .single()
                .activations
                .single()
                .produced shouldBe listOf(ManaType.GREEN)
        }

        "CR 702.10c: the EXECUTOR taps the same summoning-sick hasty source the planner offered" {
            // The other half of the same gate. A planner that admits the source and an executor that
            // refuses it would fail loudly at the CR 601.2g require — which is the point of asserting
            // the tap actually happened rather than only that a plan existed.
            val state = board("Fixture Hasty Elf" to true)
            val cost = ManaCost.parse("{G}")
            val plan = enumeratePaymentPlans(state, alice, cost).single()
            val after = payManaPlan(state, alice, cost, plan)
            after.sharedZones.battlefield
                .single { it.id == ObjectId(0) }
                .tapped shouldBe true
        }

        "CR 302.6: haste on one source does not lift the gate on a sick source without it" {
            // Both halves again, and the discriminating case: the class membership is per-object.
            val state = board("Fixture Mana Elf" to true, "Fixture Hasty Elf" to true)
            manaSourceClasses(state, alice).flatMap { it.members } shouldContainExactly listOf(ObjectId(1))
            enumeratePaymentPlans(state, alice, ManaCost.parse("{G}")) shouldHaveSize 1
            enumeratePaymentPlans(state, alice, ManaCost.parse("{G}{G}")).shouldBeEmpty()
        }

        "CR 702.10c: haste does not lift the CR 602.2a untapped requirement on a mana source" {
            // Haste answers summoning sickness only; a tapped source still has no `{T}` to pay.
            val base = board("Fixture Hasty Elf" to true)
            val tapped =
                base.copy(
                    sharedZones =
                        base.sharedZones.copy(
                            battlefield =
                                base.sharedZones.battlefield
                                    .map { if (it.id == ObjectId(0)) it.copy(tapped = true) else it }
                                    .toPersistentList(),
                        ),
                )
            manaSourceClasses(tapped, alice).shouldBeEmpty()
        }

        "CR 122.1b: a haste counter lifts the mana-payment gate through CR 613 layer 6" {
            // The keyword read is layered, not printed, so a counter-granted haste reaches both halves
            // of payment by the same route an Aura grant would.
            val base = board("Fixture Mana Elf" to true)
            val state =
                base.copy(
                    sharedZones =
                        base.sharedZones.copy(
                            battlefield =
                                base.sharedZones.battlefield
                                    .map {
                                        if (it.id == ObjectId(0)) {
                                            it.copy(
                                                counters =
                                                    persistentMapOf(Counter.KeywordCounter(Keyword.HASTE) to 1),
                                            )
                                        } else {
                                            it
                                        }
                                    }.toPersistentList(),
                        ),
                )
            manaSourceClasses(state, alice).single().members shouldContainExactly listOf(ObjectId(0))
            val cost = ManaCost.parse("{G}")
            val after = payManaPlan(state, alice, cost, enumeratePaymentPlans(state, alice, cost).single())
            after.sharedZones.battlefield
                .single { it.id == ObjectId(0) }
                .tapped shouldBe true
        }

        "ADR-005: a cast payable only off a summoning-sick hasty source IS enumerated" {
            // The end-to-end consequence: the missing option is the defect haste would otherwise cause,
            // and this is where an agent would or would not see it.
            val state = board("Fixture Hasty Elf" to true, hand = listOf("Fixture Bloom"))
            enumeratedCasts(pausedRequestOf<DecisionRequest.ChooseAction>(state)) shouldContain "Fixture Bloom"
        }
    })
