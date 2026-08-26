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

/** One-in-this-many odds the random responder takes a mulligan (CR 103.4); low so playouts terminate fast. */
private const val MULLIGAN_PROBABILITY_DENOMINATOR: Int = 4

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
            // CR 514.1 / 601.2b/h / 602.2b: any fixed-size subset selection (discards, exile, sacrifice,
            // ability discard) is a random correctly-sized subset of its options.
            is DecisionRequest.SizedSelection -> {
                val (indices, next) = randomSubset(request.optionCount, request.requiredCount, rng)
                rng = next
                Decision.MultiSelect(request.id, indices)
            }
            // Every "pick exactly one of these options" request — targets (CR 601.2c), a payment plan
            // (CR 601.2g), a trample assignment (CR 702.19e), an as-enters colour (CR 614.12), a
            // replacement ordering (CR 616.1), a library arrangement (CR 701.17a) — is a uniform pick over
            // the engine-enumerated options, each of which is independently legal (ADR-005).
            is DecisionRequest.SingleOptionSelection -> randomSingleSelect(request.id, request.optionCount)
            // CR 601.2c: a multi-target choice is a random distinct subset whose size is drawn
            // uniformly from the request's own bounds — so an "up to two" spell genuinely explores
            // taking none, one, and two. Distinct indices are the same-object rule (CR 601.2c), and
            // `randomSubset` guarantees them, so this responder cannot produce an illegal combination.
            is DecisionRequest.RangedSelection -> {
                val (size, afterSize) = rng.nextInt(request.maximumCount - request.minimumCount + 1)
                val (indices, next) = randomSubset(request.optionCount, request.minimumCount + size, afterSize)
                rng = next
                Decision.MultiSelect(request.id, indices)
            }
            // CR 601.2b/701.60a: a summed-weight selection (collect evidence) takes options in a random
            // order until the threshold is reached, so a run genuinely explores different payments of
            // the same cost rather than always the cheapest one. It stops at the threshold rather than
            // going on, so an over-payment is possible only through a heavy last card — which is the
            // honest distribution, since exiling more than needed is a choice and not an accident.
            is DecisionRequest.SummedSelection -> {
                val (order, next) = (0 until request.optionCount).toList().toPersistentList().shuffled(rng)
                rng = next
                var total = 0
                val chosen =
                    order.takeWhile { index ->
                        val short = total < request.requiredTotal
                        total += request.optionWeights[index]
                        short
                    }
                Decision.MultiSelect(request.id, chosen)
            }
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
            // CR 509.2 / 603.3b: a uniformly random permutation of the options, via the frozen shuffle —
            // shared by the blocker order and the trigger order.
            is DecisionRequest.PermutationSelection -> {
                val (order, next) = (0 until request.permutationSize).toList().toPersistentList().shuffled(rng)
                rng = next
                Decision.MultiSelect(request.id, order)
            }
            // CR 702.35b: a fair coin decides whether to accept a "you may" (madness reflexive cast).
            is DecisionRequest.ChooseYesNo -> randomSingleSelect(request.id, DecisionRequest.ChooseYesNo.OPTION_COUNT)
            // A "choose one of these, or opt out" choice (CR 701.16 keep-one, CR 601.3b cost-mode, CR 701.18
            // find-one): a uniform pick over all its indices — the real options plus the one opt-out.
            is DecisionRequest.ChoiceCountSelection -> randomSingleSelect(request.id, request.choiceCount)
            // CR 103.4/103.5: mulligan with a modest probability (so playouts terminate quickly), and
            // bottom a random correctly-sized selection of the hand.
            is DecisionRequest.MulliganRequest -> randomMulligan(request)
        }

    private fun randomMulligan(request: DecisionRequest.MulliganRequest): Decision =
        when (request) {
            is DecisionRequest.ChooseMulligan -> {
                val (roll, next) = rng.nextInt(MULLIGAN_PROBABILITY_DENOMINATOR)
                rng = next
                val index =
                    if (roll == 0) DecisionRequest.ChooseMulligan.MULLIGAN else DecisionRequest.ChooseMulligan.KEEP
                Decision.SingleSelect(request.id, index)
            }
            is DecisionRequest.ChooseCardsToBottom -> {
                val (indices, next) = randomSubset(request.options.size, request.count, rng)
                rng = next
                Decision.MultiSelect(request.id, indices)
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
