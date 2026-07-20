package dev.mtgplay.acceptance.driver

import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.random.shuffled
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import kotlinx.collections.immutable.toPersistentList

/** Generous turn cap for lands-only random playouts: they deck out near turn 108 (CR 104.3c). */
const val LANDS_ONLY_TURN_CAP: Int = 130

/**
 * A [Responder] that plays a uniformly random *legal* decision — the seed of the Phase 3 fuzz
 * harness (PLAN.md §2.3).
 *
 * Because every legal option is engine-enumerated (ADR-005), "random legal" is just a uniform pick
 * over the enumerated indices: a random option for a priority window, and a random correctly-sized
 * subset of the hand for a cleanup discard. Nothing illegal is representable, so the responder
 * cannot produce an illegal decision.
 *
 * **All randomness flows through the seeded core [Rng]** (ADR-006) — never `kotlin.random` — so a
 * `(config seed, responder seed)` pair reproduces a playout exactly. The generator is threaded
 * across calls through a single private field; give each game its own responder.
 *
 * @param seed the seed for this responder's decision randomness.
 */
class RandomLegalResponder(
    seed: Long,
) : Responder {
    private var rng: Rng = Rng(seed)

    override fun respond(
        request: DecisionRequest,
        state: GameState,
    ): Decision =
        when (request) {
            is DecisionRequest.ChooseAction -> {
                val (index, next) = rng.nextInt(request.options.size)
                rng = next
                Decision.SingleSelect(request.id, index)
            }
            is DecisionRequest.ChooseDiscards -> {
                val (indices, next) = randomSubset(request.options.size, request.count, rng)
                rng = next
                Decision.MultiSelect(request.id, indices)
            }
        }

    private fun randomSubset(
        size: Int,
        count: Int,
        generator: Rng,
    ): Pair<List<Int>, Rng> {
        // A partial Fisher-Yates via the frozen shuffle: uniformly pick `count` distinct indices.
        val (shuffled, next) = (0 until size).toList().toPersistentList().shuffled(generator)
        return shuffled.take(count) to next
    }
}
