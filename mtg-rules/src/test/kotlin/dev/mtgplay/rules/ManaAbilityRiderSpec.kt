package dev.mtgplay.rules

import dev.mtgplay.core.definition.ManaAbilityRider
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.mana.ManaCost
import dev.mtgplay.core.mana.ManaType
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.engine.enumeratePaymentPlans
import dev.mtgplay.rules.engine.manaSourceClasses
import dev.mtgplay.rules.engine.payManaPlan
import dev.mtgplay.rules.engine.player
import dev.mtgplay.rules.engine.productionProfile
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.collections.immutable.toPersistentList

/**
 * `W8-B` — a CR 605.1a mana ability with a **non-mana rider**: "{T}: Add {B}. This creature deals 1
 * damage to you" (Elves of Deep Shadow), carried by [fixtureBloodElf].
 *
 * The property under test is not "damage happens". It is that the rider changes **nothing** about the
 * ability's classification, which is the whole reason the field exists on
 * [dev.mtgplay.core.definition.ManaAbility] rather than the card being demoted to an activated
 * ability. Three separate things had to stay true, and each of them is a different way to lose the
 * card:
 *
 * - the ability is still stackless and still enumerable as a payment source (CR 605.1a, CR 605.3a);
 * - the rider is not a **cost**, so nothing gates activation on being able to survive it (ADR-005 —
 *   refusing to enumerate a lethal-but-legal line removes a real line of play);
 * - the rider still *happens*, dealt by the source as damage rather than as bare life loss
 *   (CR 120.1), so prevention and protection remain applicable to it.
 */
class ManaAbilityRiderSpec :
    StringSpec({

        val bloodElf = "Fixture Blood Elf"

        /**
         * Alice's named permanents, settled (not summoning sick) unless [sick] says otherwise — a
         * [GameObject] defaults to sick, and a sick creature is in no source class at all, which would
         * make every assertion below vacuous.
         */
        fun board(
            vararg permanents: String,
            life: Int = STARTING_LIFE,
            sick: Boolean = false,
        ): GameState {
            val base =
                fixtureState(
                    aliceSetup = SeatSetup(life = life, battlefield = permanents.toList()),
                    bobSetup = SeatSetup(),
                )
            val battlefield =
                base.sharedZones.battlefield.mapIndexed { index, obj ->
                    if (index < permanents.size) obj.copy(summoningSick = sick) else obj
                }
            return base.copy(sharedZones = base.sharedZones.copy(battlefield = battlefield.toPersistentList()))
        }

        "CR 605.1a: a rider does not stop the ability being a mana ability — it is still a payment source" {
            val state = board(bloodElf)
            manaSourceClasses(state, alice) shouldHaveSize 1
            val plans = enumeratePaymentPlans(state, alice, ManaCost.parse("{B}"))
            plans shouldHaveSize 1
            plans
                .single()
                .activations
                .single()
                .produced shouldBe listOf(ManaType.BLACK)
        }

        "CR 605.1a: the rider travels on the production alternative the executor is handed" {
            val state = board(bloodElf)
            val source = state.sharedZones.battlefield.single()
            val profile = productionProfile(state, source)
            profile.shouldNotBeNull()
            profile.single().rider shouldBe ManaAbilityRider.DamageToController(FIXTURE_BLOOD_ELF_DAMAGE)
        }

        "docs/design/mana-payment.md §2: a rider makes two otherwise identical sources different classes" {
            // The equivalence relation keys on the profile, and the profile now carries the rider. A
            // source charging life and one that does not are genuinely not interchangeable, and
            // collapsing them would hide a real choice from an agent (ADR-005).
            val state = board(bloodElf)
            val riderKey = manaSourceClasses(state, alice).single().key
            val plainKey = manaSourceClasses(board("Fixture Mana Elf"), alice).single().key
            riderKey.profile shouldNotBe plainKey.profile
        }

        "CR 605.1a: executing the plan adds the mana AND performs the rider, in that order" {
            val state = board(bloodElf)
            val cost = ManaCost.parse("{B}")
            val plan = enumeratePaymentPlans(state, alice, cost).single()
            val after = payManaPlan(state, alice, cost, plan)

            // The mana was produced and spent — the payment is exact, as every fixture payment is.
            after
                .player(alice)
                .manaPool
                .shouldBeEmpty()
            after.sharedZones.battlefield
                .single { it.id == ObjectId(0) }
                .tapped shouldBe true
            // …and the rider ran.
            after.player(alice).life shouldBe STARTING_LIFE - FIXTURE_BLOOD_ELF_DAMAGE
        }

        "CR 120.1: the rider is damage from the source, not bare life loss" {
            // Damage has a source, which is what makes CR 615 prevention and CR 702.16e protection
            // applicable to it; a `loseLife` would be silently immune to both.
            val state = board(bloodElf)
            val cost = ManaCost.parse("{B}")
            val after = payManaPlan(state, alice, cost, enumeratePaymentPlans(state, alice, cost).single())
            val dealt = after.events.filterIsInstance<GameEvent.DamageDealt>().single()
            dealt.source.objectId shouldBe ObjectId(0)
            dealt.source.card.name shouldBe bloodElf
            dealt.amount shouldBe FIXTURE_BLOOD_ELF_DAMAGE
        }

        "ADR-005: a controller at 1 life is still offered the plan — the rider is not a cost" {
            // Tapping the Elf at 1 life is legal Magic: the mana arrives, the damage takes the player
            // to 0, and CR 704.5a ends the game at the *next* state-based check (CR 704.3), never
            // inside the payment. Sometimes the spell being paid for wins first. Refusing to
            // enumerate it would delete a real line.
            val state = board(bloodElf, life = 1)
            val cost = ManaCost.parse("{B}")
            enumeratePaymentPlans(state, alice, cost) shouldHaveSize 1
            val after = payManaPlan(state, alice, cost, enumeratePaymentPlans(state, alice, cost).single())
            after.player(alice).life shouldBe 0
        }

        "CR 302.6: the rider does not lift the summoning-sickness gate on the {T} half" {
            // The rider is not a cost, and it is also not a licence: the ability's cost still has {T}
            // in it, so a Blood Elf played this turn is no mana source at all.
            val sick = board(bloodElf, sick = true)
            manaSourceClasses(sick, alice).shouldBeEmpty()
            sick.player(alice).life shouldBe STARTING_LIFE
        }
    })
