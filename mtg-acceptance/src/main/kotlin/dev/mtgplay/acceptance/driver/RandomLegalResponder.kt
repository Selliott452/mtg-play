package dev.mtgplay.acceptance.driver

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.random.shuffled
import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
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
            is DecisionRequest.ChooseAction -> randomSingleSelect(request.id, request.options.size)
            is DecisionRequest.ChooseDiscards -> {
                val (indices, next) = randomSubset(request.options.size, request.count, rng)
                rng = next
                Decision.MultiSelect(request.id, indices)
            }
            // The casting requests (CR 601.2c, CR 601.2g) are uniform picks over the
            // engine-enumerated legal targets and payment plans (ADR-005).
            is DecisionRequest.ChooseTargets -> randomSingleSelect(request.id, request.options.size)
            is DecisionRequest.ChoosePaymentPlan -> randomSingleSelect(request.id, request.options.size)
            // CR 508.1: attack with a random subset of the eligible attackers (each independently
            // in or out) — the empty subset is legal, so the responder may declare no attackers.
            is DecisionRequest.DeclareAttackers -> {
                val (indices, next) = randomSubsetAnySize(request.options.size, rng)
                rng = next
                Decision.MultiSelect(request.id, indices)
            }
            // CR 509.1: a random legal block assignment — each blocker blocks nothing or one of
            // the attackers it may legally block (CR 509.1a: at most one attacker per blocker).
            is DecisionRequest.DeclareBlockers -> {
                val (indices, next) = randomBlockAssignment(request.options, rng)
                rng = next
                Decision.MultiSelect(request.id, indices)
            }
            // CR 509.2: a uniformly random permutation of the blockers, via the frozen shuffle.
            is DecisionRequest.OrderBlockers -> {
                val (order, next) = (0 until request.options.size).toList().toPersistentList().shuffled(rng)
                rng = next
                Decision.MultiSelect(request.id, order)
            }
        }

    private fun randomSingleSelect(
        id: DecisionRequestId,
        optionCount: Int,
    ): Decision.SingleSelect {
        val (index, next) = rng.nextInt(optionCount)
        rng = next
        return Decision.SingleSelect(id, index)
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

    // A subset of [0, size) of any size — each index included on an independent fair coin flip.
    private fun randomSubsetAnySize(
        size: Int,
        generator: Rng,
    ): Pair<List<Int>, Rng> {
        var current = generator
        val chosen =
            (0 until size).filter { _ ->
                val (bit, next) = current.nextInt(2)
                current = next
                bit == 0
            }
        return chosen to current
    }

    // For each blocker (in option order), choose to block nothing or exactly one of the attackers
    // it may block (CR 509.1a) — a uniformly random legal block assignment, as option indices.
    private fun randomBlockAssignment(
        options: List<DecisionRequest.DeclareBlockers.Option>,
        generator: Rng,
    ): Pair<List<Int>, Rng> {
        val optionsByBlocker = LinkedHashMap<ObjectId, MutableList<Int>>()
        options.forEachIndexed { index, option ->
            optionsByBlocker.getOrPut(option.blocker) { mutableListOf() }.add(index)
        }
        var current = generator
        val chosen = mutableListOf<Int>()
        for (blockerOptions in optionsByBlocker.values) {
            // choices are: block via one of blockerOptions, or (the extra slot) don't block.
            val (pick, next) = current.nextInt(blockerOptions.size + 1)
            current = next
            if (pick < blockerOptions.size) chosen.add(blockerOptions[pick])
        }
        return chosen to current
    }
}
