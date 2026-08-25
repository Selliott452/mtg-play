package dev.mtgplay.rules

import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.event.GameEvent
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.state.ContinuousModification
import dev.mtgplay.core.state.EffectDuration
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.effect.applyUntilEndOfTurn
import dev.mtgplay.rules.engine.applicableStateBasedActions
import dev.mtgplay.rules.engine.cleanupRemoveDamageAndEndEffects
import dev.mtgplay.rules.engine.effectiveKeywords
import dev.mtgplay.rules.engine.layeredCharacteristics
import dev.mtgplay.rules.engine.layeredPower
import dev.mtgplay.rules.engine.layeredToughness
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList

/**
 * The `FW-DURATION` until-end-of-turn continuous-effect framework (CR 611.2, CR 514.2, CR 613.7d),
 * exercised entirely by fixture objects — `mtg-rules` names no card (docs/design/duration.md).
 *
 * The two properties the whole framework turns on are the first two tests: a resolution-generated
 * magnitude is **snapshotted** and does not track the board (CR 611.2d), while a static Aura's
 * dynamic magnitude still does (CR 613.3c). Run against the same board mutation, they are the
 * difference between Basilisk Gate being right and being subtly wrong
 * (docs/gauntlet-card-triage.md T16).
 */
class DurationSpec :
    StringSpec({

        "CR 611.2d: a snapshotted until-end-of-turn magnitude does not track the board after it is created" {
            // An "X = enchantments on the battlefield" count is performed *by the resolution* and handed
            // over as an integer; here X is 1 at creation time.
            val before = auraState(listOf(bfObject(0, "Ent"), bfObject(1, "Fixture Mask", attachedTo = 0)))
            val created = pump(before, affected = 0, amount = 1)
            // Ent is 2/2 printed, +1/+1 from the Mask (N = 1), +1/+1 from the frozen timed effect.
            layeredPower(created, ObjectId(0)) shouldBe 4
            layeredToughness(created, ObjectId(0)) shouldBe 4

            // A second enchantment enters. The Mask recounts (CR 613.3c); the timed effect must not.
            val grown = withAnotherEnchantment(created)
            // Mask is now +2/+2 (N = 2); the timed effect is still exactly +1/+1. 2 + 2 + 1 = 5.
            layeredPower(grown, ObjectId(0)) shouldBe 5
            layeredToughness(grown, ObjectId(0)) shouldBe 5
        }

        "CR 613.3c: a static Aura's dynamic magnitude still recounts, on the same board that carries a timed effect" {
            // The mirror of the test above, proving the two semantics are genuinely different rather
            // than merely documented as different: with the Mask removed, the same board mutation moves
            // nothing at all, because only the frozen timed effect remains.
            val onlyTimed = pump(auraState(listOf(bfObject(0, "Ent"))), affected = 0, amount = 1)
            layeredPower(onlyTimed, ObjectId(0)) shouldBe 3
            layeredPower(withAnotherEnchantment(onlyTimed), ObjectId(0)) shouldBe 3
        }

        "CR 613.4d: an until-end-of-turn modifier applies in sublayer 7c, additively with an Aura's" {
            val state = auraState(listOf(bfObject(0, "Ent"), bfObject(1, "Fixture Cloak", attachedTo = 0)))
            val pumped = pump(state, affected = 0, amount = 3)
            // 2/2 printed + 2/+2 Cloak + 3/+3 timed.
            layeredCharacteristics(pumped, ObjectId(0)).let {
                it.power shouldBe 7
                it.toughness shouldBe 7
                // Layer isolation holds across generators: the timed 7c modifier adds no keyword.
                it.keywords.shouldContainExactlyInAnyOrder(Keyword.FIRST_STRIKE)
            }
        }

        "CR 613.1f: an until-end-of-turn keyword grant applies in layer 6 and reaches the effective seam" {
            val state = auraState(listOf(bfObject(0, "Ent")))
            val granted =
                applyUntilEndOfTurn(
                    state = state,
                    affected = ObjectId(0),
                    modification =
                        ContinuousModification(
                            grantedKeywords = persistentSetOf(Keyword.HEXPROOF, Keyword.INDESTRUCTIBLE),
                        ),
                    sourceCard = CardRef("Fixture Safekeeping"),
                )
            effectiveKeywords(granted, ObjectId(0))
                .shouldContainExactlyInAnyOrder(Keyword.HEXPROOF, Keyword.INDESTRUCTIBLE)
            // A pure layer-6 grant moves no power or toughness.
            layeredPower(granted, ObjectId(0)) shouldBe 2
            layeredToughness(granted, ObjectId(0)) shouldBe 2
        }

        "CR 613.4d: a negative until-end-of-turn modifier lowers power without touching toughness" {
            val state = auraState(listOf(bfObject(0, "Ent")))
            val weakened =
                applyUntilEndOfTurn(
                    state = state,
                    affected = ObjectId(0),
                    modification = ContinuousModification(powerMod = -2),
                    sourceCard = CardRef("Fixture Intruder"),
                )
            layeredPower(weakened, ObjectId(0)) shouldBe 0
            layeredToughness(weakened, ObjectId(0)) shouldBe 2
        }

        "CR 613.7d: a timed effect's timestamp comes from the shared allocation sequence, above every object id" {
            val state = auraState(listOf(bfObject(0, "Ent"), bfObject(1, "Fixture Cloak", attachedTo = 0)))
            val counterBefore = state.nextObjectId
            val created = pump(state, affected = 0, amount = 1)
            val effect = created.timedEffects.single()
            // One sequence: the timestamp is the next value the counter would have minted for an object,
            // so it is strictly greater than every Aura's battlefield-entry timestamp and comparable to it.
            effect.timestamp shouldBe counterBefore
            created.nextObjectId shouldBe counterBefore + 1
            created.sharedZones.battlefield.forEach { it.id.value shouldBeLessThan effect.timestamp }
            effect.duration shouldBe EffectDuration.UntilEndOfTurn
            effect.createdOnTurn shouldBe created.turn.number
        }

        "CR 611.2c: a timed effect whose affected object has left the battlefield applies to nothing" {
            // Two 2/2 bodies; only the first is pumped.
            val state = auraState(listOf(bfObject(0, "Ent"), bfObject(2, "Ent")))
            val created = pump(state, affected = 0, amount = 3)
            layeredPower(created, ObjectId(0)) shouldBe 5
            layeredPower(created, ObjectId(2)) shouldBe 2

            // CR 400.7: the pumped Ent dies and is reborn elsewhere under a fresh id. The effect stays
            // in the store until CR 514.2 and simply modifies nothing — it must not follow the card,
            // must not leak onto another object, and must not be an error.
            val dead =
                created.copy(
                    sharedZones =
                        created.sharedZones.copy(
                            battlefield =
                                created.sharedZones.battlefield
                                    .filterNot { it.id == ObjectId(0) }
                                    .toPersistentList(),
                        ),
                )
            dead.timedEffects.size shouldBe 1
            layeredPower(dead, ObjectId(2)) shouldBe 2
        }

        "CR 611.2c: creating an until-end-of-turn effect on an object off the battlefield fails loudly" {
            val state = auraState(listOf(bfObject(0, "Ent")))
            val error =
                shouldThrow<IllegalArgumentException> {
                    applyUntilEndOfTurn(
                        state = state,
                        affected = ObjectId(99),
                        modification = ContinuousModification(powerMod = 1),
                        sourceCard = CardRef("Fixture Pump"),
                    )
                }
            error.message.shouldBeInstanceOf<String>() shouldContain "611.2c"
        }

        "CR 613: an until-end-of-turn effect that grants nothing and modifies nothing fails loudly" {
            // The loud gate at creation: an unimplemented effect kind must never reach the store
            // (docs/design/layer-system.md §1).
            val error = shouldThrow<IllegalArgumentException> { ContinuousModification() }
            error.message.shouldBeInstanceOf<String>() shouldContain "613"
        }

        "CR 514.2: the cleanup turn-based action ends every until-end-of-turn effect" {
            val state = auraState(listOf(bfObject(0, "Ent"), bfObject(1, "Fixture Cloak", attachedTo = 0)))
            val pumped = pump(state, affected = 0, amount = 3)
            layeredPower(pumped, ObjectId(0)) shouldBe 7
            val cleaned = cleanupRemoveDamageAndEndEffects(pumped)
            cleaned.timedEffects.shouldBeEmpty()
            // The Aura's static effect is untouched — only the duration expired.
            layeredPower(cleaned, ObjectId(0)) shouldBe 4
        }

        "CR 514.2 headline: a creature kept alive by a pump survives cleanup, because damage removal is simultaneous" {
            // Ent 2/2 pumped to 5/5 takes 4 combat damage and survives (4 < 5). At cleanup the pump ends
            // and the damage is removed *at the same time* — sequencing the wear-off first would leave a
            // 2/2 with 4 marked damage and kill it to CR 704.5g, a reachable, silently-wrong death.
            val state = auraState(listOf(bfObject(0, "Ent", damageMarked = 4)))
            val pumped = pump(state, affected = 0, amount = 3)
            layeredToughness(pumped, ObjectId(0)) shouldBe 5
            applicableStateBasedActions(pumped).shouldBeEmpty()

            val cleaned = cleanupRemoveDamageAndEndEffects(pumped)
            cleaned.sharedZones.battlefield
                .single { it.id == ObjectId(0) }
                .damageMarked shouldBe 0
            cleaned.timedEffects.shouldBeEmpty()
            layeredToughness(cleaned, ObjectId(0)) shouldBe 2
            // The whole point: no death state-based action is applicable after the simultaneous cleanup.
            applicableStateBasedActions(cleaned).shouldBeEmpty()
        }

        "CR 611.2: creating an until-end-of-turn effect narrates it with its snapshotted modifiers" {
            val state = auraState(listOf(bfObject(0, "Ent")))
            val created = pump(state, affected = 0, amount = 3)
            created.events.last() shouldBe
                GameEvent.ContinuousEffectCreated(
                    sourceCard = CardRef("Fixture Pump"),
                    affected = ObjectId(0),
                    powerMod = 3,
                    toughnessMod = 3,
                )
        }
    })

/** A fixture "+[amount]/+[amount] until end of turn" on the battlefield object [affected]. */
private fun pump(
    state: GameState,
    affected: Long,
    amount: Int,
): GameState =
    applyUntilEndOfTurn(
        state = state,
        affected = ObjectId(affected),
        modification = ContinuousModification(powerMod = amount, toughnessMod = amount),
        sourceCard = CardRef("Fixture Pump"),
    )

private infix fun Long.shouldBeLessThan(other: Long) {
    (this < other) shouldBe true
}

/**
 * [state] with a further enchantment on the battlefield — an enchanted Meadow, whose ids continue the
 * allocation sequence. What a `Magnitude.Dynamic` counting enchantments must notice, and what a
 * snapshotted magnitude must not.
 */
private fun withAnotherEnchantment(state: GameState): GameState {
    val landId = state.nextObjectId
    val auraId = landId + 1
    return state.copy(
        sharedZones =
            state.sharedZones.copy(
                battlefield =
                    state.sharedZones.battlefield
                        .adding(bfObject(landId, "Meadow"))
                        .adding(bfObject(auraId, "Fixture Growth", attachedTo = landId)),
            ),
        nextObjectId = auraId + 1,
    )
}
