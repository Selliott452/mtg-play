package dev.mtgplay.acceptance.invariant

import dev.mtgplay.acceptance.STARTING_LIFE
import dev.mtgplay.acceptance.alice
import dev.mtgplay.acceptance.bob
import dev.mtgplay.core.card.Keyword
import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.state.ContinuousModification
import dev.mtgplay.core.state.EffectDuration
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.GameState
import dev.mtgplay.core.state.PlayerState
import dev.mtgplay.core.state.SharedZones
import dev.mtgplay.core.state.TimedContinuousEffect
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList

/**
 * The [Invariant.TIMED_EFFECT_SANITY] check (`FW-DURATION`, docs/design/duration.md §8): every
 * running resolution-generated continuous effect is well-formed (CR 611.2, CR 514.2, CR 613.7d).
 *
 * Each property is tested against corruption the engine cannot reach — which is the point of the
 * check: the failure it guards is an effect that *should* have expired and did not, and no state a
 * correct engine produces exhibits it. The last two tests pin what the check deliberately tolerates,
 * because tightening either of them would fail on ordinary play.
 */
class TimedEffectSanitySpec :
    StringSpec({

        "CR 514.2: an until-end-of-turn effect surviving into a later turn is one TIMED_EFFECT_SANITY violation" {
            // The whole duration contract: the cleanup turn-based action must have ended this.
            val stale = pumpEffect(timestamp = 10, createdOnTurn = TURN - 1)
            val state = timedState(effects = listOf(stale))
            checkTimedEffectSanity(state).map { it.invariant } shouldContainExactly
                listOf(Invariant.TIMED_EFFECT_SANITY)
        }

        "CR 400.7: a timestamp at or above the allocation counter is one TIMED_EFFECT_SANITY violation" {
            // Timed effects and objects draw from one monotonic sequence, so the same bound applies.
            val state = timedState(effects = listOf(pumpEffect(timestamp = NEXT_OBJECT_ID)))
            checkTimedEffectSanity(state).map { it.invariant } shouldContainExactly
                listOf(Invariant.TIMED_EFFECT_SANITY)
        }

        "CR 613.7d: timestamps out of creation order are one TIMED_EFFECT_SANITY violation" {
            // The store is append-only, so store order is timestamp order — the property the CR 613.7
            // within-layer sort and the fingerprint's order-stability both rest on.
            val state = timedState(effects = listOf(pumpEffect(timestamp = 20), pumpEffect(timestamp = 10)))
            checkTimedEffectSanity(state).map { it.invariant } shouldContainExactly
                listOf(Invariant.TIMED_EFFECT_SANITY)
        }

        "CR 611.2c: an effect whose affected object has left the battlefield is deliberately clean" {
            // The one place an ATTACHMENT_INTEGRITY-shaped dangling-reference check would be *wrong*: a
            // CR 611.2 effect does not end when its object dies, it just applies to nothing (CR 400.7).
            val state = timedState(effects = listOf(pumpEffect(timestamp = 10, affected = ObjectId(404))))
            checkTimedEffectSanity(state).shouldBeEmpty()
        }

        "an effect created this turn, in order and below the counter, is clean" {
            val state =
                timedState(
                    effects =
                        listOf(
                            pumpEffect(timestamp = 10),
                            TimedContinuousEffect(
                                affected = ObjectId(0),
                                modification =
                                    ContinuousModification(grantedKeywords = persistentSetOf(Keyword.HEXPROOF)),
                                duration = EffectDuration.UntilEndOfTurn,
                                timestamp = 11,
                                createdOnTurn = TURN,
                                source = null,
                                sourceCard = CardRef("Test Safekeeping"),
                            ),
                        ),
                )
            checkTimedEffectSanity(state).shouldBeEmpty()
            // And a state with no running effects at all is trivially clean.
            checkTimedEffectSanity(timedState(effects = emptyList())).shouldBeEmpty()
        }

        "the checker runs TIMED_EFFECT_SANITY as part of its whole-state check" {
            val stale = pumpEffect(timestamp = 10, createdOnTurn = TURN - 1)
            InvariantChecker.check(timedState(effects = listOf(stale))).map { it.invariant } shouldBe
                listOf(Invariant.TIMED_EFFECT_SANITY)
        }
    })

private const val TURN: Int = 4
private const val NEXT_OBJECT_ID: Long = 50

/** A `+1/+1 until end of turn` fixture effect. */
private fun pumpEffect(
    timestamp: Long,
    createdOnTurn: Int = TURN,
    affected: ObjectId = ObjectId(0),
): TimedContinuousEffect =
    TimedContinuousEffect(
        affected = affected,
        modification = ContinuousModification(powerMod = 1, toughnessMod = 1),
        duration = EffectDuration.UntilEndOfTurn,
        timestamp = timestamp,
        createdOnTurn = createdOnTurn,
        source = ObjectId(1),
        sourceCard = CardRef("Test Pump"),
    )

/**
 * A paused two-player state on turn [TURN] carrying [effects], with one inert battlefield object so
 * the affected id names something real in the clean cases. Definitions are deliberately absent: the
 * check reads only the store, never a card's behaviour.
 */
private fun timedState(effects: List<TimedContinuousEffect>): GameState {
    fun seat() = PlayerState(STARTING_LIFE, persistentListOf(), persistentListOf(), persistentListOf())
    return GameState(
        players = persistentMapOf(alice to seat(), bob to seat()),
        turn = Turn(alice, TURN, TurnPhase.PRECOMBAT_MAIN, null),
        sharedZones =
            SharedZones(
                battlefield = persistentListOf(GameObject(ObjectId(0), CardRef("Test Bear"), alice)),
                stack = persistentListOf(),
                exile = persistentListOf(),
            ),
        nextObjectId = NEXT_OBJECT_ID,
        rng = Rng(0),
        events = persistentListOf(),
        timedEffects = effects.toPersistentList(),
    )
}
