package dev.mtgplay.rules

import dev.mtgplay.core.identity.CardRef
import dev.mtgplay.core.state.GameObject
import dev.mtgplay.core.state.Turn
import dev.mtgplay.core.state.TurnPhase
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequestId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.collections.immutable.persistentListOf

private fun overfullCleanupState() =
    twoPlayerState(
        turn = Turn(alice, 3, TurnPhase.ENDING, dev.mtgplay.core.state.TurnStep.CLEANUP),
        aliceState = playerWithZones(hand = mountains(0L..8L, alice)),
        bobState = playerWithZones(library = mountains(20L..22L, bob)),
        nextObjectId = 100,
    )

/**
 * Decision misuse fails loudly (ADR-004): wrong request, wrong shape, out-of-range indices,
 * wrong arity — replay integrity (ADR-006) depends on none of these being tolerated.
 */
class DecisionMisuseSpec :
    StringSpec({
        val engine = DefaultGameEngine()

        fun firstTwoPauses(): Pair<AdvanceResult.NeedsDecision, AdvanceResult.NeedsDecision> {
            val first = engine.start(mountainConfig()).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val second =
                engine.advance(first.state, respondTo(first.request)).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            return first to second
        }

        "a decision answering a different request than the pending one is rejected" {
            val (first, second) = firstTwoPauses()
            val stale = respondTo(first.request)
            shouldThrow<IllegalArgumentException> { engine.advance(second.state, stale) }
        }

        "a stale ordinal for the right seat is rejected (replay integrity, ADR-006)" {
            val (_, second) = firstTwoPauses()
            val third =
                engine
                    .advance(second.state, respondTo(second.request))
                    .shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            // Third pause is alice's second window: her ordinal is now 1, not 0.
            third.request.id shouldBe DecisionRequestId(alice, 1)
            val stale = Decision.SingleSelect(DecisionRequestId(alice, 0), 0)
            shouldThrow<IllegalArgumentException> { engine.advance(third.state, stale) }
        }

        "an out-of-range option index is rejected" {
            val first = engine.start(mountainConfig()).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            shouldThrow<IllegalArgumentException> {
                engine.advance(first.state, Decision.SingleSelect(first.request.id, 1))
            }
            shouldThrow<IllegalArgumentException> {
                engine.advance(first.state, Decision.SingleSelect(first.request.id, -1))
            }
        }

        "a multi-select answering a priority window is rejected (wrong decision shape)" {
            val first = engine.start(mountainConfig()).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            shouldThrow<IllegalArgumentException> {
                engine.advance(first.state, Decision.MultiSelect(first.request.id, listOf(0)))
            }
        }

        "CR 514.1: wrong discard arity is rejected — too few, too many, duplicated, out of range, wrong shape" {
            val state = overfullCleanupState()
            val id = DecisionRequestId(alice, 0)
            shouldThrow<IllegalArgumentException> { engine.advance(state, Decision.MultiSelect(id, listOf(0))) }
            shouldThrow<IllegalArgumentException> { engine.advance(state, Decision.MultiSelect(id, listOf(0, 1, 2))) }
            shouldThrow<IllegalArgumentException> { engine.advance(state, Decision.MultiSelect(id, listOf(1, 1))) }
            shouldThrow<IllegalArgumentException> { engine.advance(state, Decision.MultiSelect(id, listOf(0, 42))) }
            shouldThrow<IllegalArgumentException> { engine.advance(state, Decision.SingleSelect(id, 0)) }
        }

        "advancing a state that is not paused at a decision point fails loudly (ADR-004)" {
            val idle =
                twoPlayerState(
                    turn = Turn(alice, 2, TurnPhase.PRECOMBAT_MAIN, null),
                    aliceState = playerWithZones(library = mountains(0L..3L, alice)),
                    bobState = playerWithZones(library = mountains(10L..13L, bob)),
                    nextObjectId = 50,
                )
            shouldThrow<IllegalStateException> {
                engine.advance(idle, Decision.SingleSelect(DecisionRequestId(alice, 0), 0))
            }
        }

        "TODO P2.1: all players passing over a nonempty stack fails loudly (CR 608 not yet implemented)" {
            val first = engine.start(mountainConfig()).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val (spellId, allocated) = first.state.allocateObjectId()
            val stackObject = GameObject(spellId, CardRef("Mountain"), alice)
            val withStack =
                allocated.copy(
                    sharedZones = allocated.sharedZones.copy(stack = persistentListOf(stackObject)),
                )
            val second =
                engine.advance(withStack, respondTo(first.request)).shouldBeInstanceOf<AdvanceResult.NeedsDecision>()
            val error =
                shouldThrow<IllegalStateException> { engine.advance(second.state, respondTo(second.request)) }
            error.message.shouldBeInstanceOf<String>() shouldContain "P2.1"
        }
    })
