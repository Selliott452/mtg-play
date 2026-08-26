package dev.mtgplay.cli

import dev.mtgplay.core.identity.ObjectId
import dev.mtgplay.core.random.Rng
import dev.mtgplay.core.random.shuffled
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId
import kotlinx.collections.immutable.toPersistentList

/**
 * The vs-random opponent (P6.4 deliverable 4): a uniformly random *legal* chooser, reimplemented
 * against the public engine surface (the CLI may not depend on `mtg-acceptance`).
 *
 * Because every legal option is engine-enumerated (ADR-005), "random legal" is a uniform pick over
 * the enumerated indices - nothing illegal is representable. **All randomness flows through the
 * seeded core [Rng]** (ADR-006), never `kotlin.random`, so `(match seed -> chooser seed)` reproduces
 * the opponent's whole play. The generator is threaded through a single private field; give each
 * game its own chooser.
 *
 * @param seed the seed for this chooser's decision randomness (the CLI derives it from the match seed).
 */
class RandomLegalChooser(
    seed: Long,
) {
    private var rng: Rng = Rng(seed)

    /** A uniformly random legal answer to [request]. */
    fun choose(request: DecisionRequest): Decision =
        when (request) {
            is DecisionRequest.ChooseAction -> single(request.id, request.options.size)
            is DecisionRequest.SingleOptionSelection -> single(request.id, request.optionCount)
            is DecisionRequest.ChooseYesNo -> single(request.id, DecisionRequest.ChooseYesNo.OPTION_COUNT)
            is DecisionRequest.ChoiceCountSelection -> single(request.id, request.choiceCount)
            is DecisionRequest.SizedSelection -> multi(request.id, subset(request.optionCount, request.requiredCount))
            // CR 601.2c: a size drawn uniformly from the request's bounds, then that many distinct
            // indices — distinctness being the same-object rule, guaranteed by `subset`.
            is DecisionRequest.RangedSelection ->
                multi(request.id, subset(request.optionCount, rangedSize(request)))
            // CR 601.2b/701.60a: options taken in a random order until their weights reach the
            // threshold, so a run explores different payments of the same cost.
            is DecisionRequest.SummedSelection -> multi(request.id, summedPayment(request))
            is DecisionRequest.PermutationSelection -> multi(request.id, permutation(request.permutationSize))
            // CR 508.1/508.1d: a random subset, plus every attacker the declaration is *required* to
            // include (CR 701.38a). A chooser that can produce an illegal answer is worse than useless
            // to the fuzz harness — the crash it reports would be its own.
            is DecisionRequest.DeclareAttackers ->
                multi(request.id, (anySizeSubset(request.options.size).toSet() + request.requiredIndices).sorted())
            is DecisionRequest.DeclareBlockers -> multi(request.id, blockAssignment(request.options))
            is DecisionRequest.MulliganRequest -> mulligan(request)
        }

    private fun mulligan(request: DecisionRequest.MulliganRequest): Decision =
        when (request) {
            is DecisionRequest.ChooseMulligan -> single(request.id, DecisionRequest.ChooseMulligan.OPTION_COUNT)
            is DecisionRequest.ChooseCardsToBottom -> multi(request.id, subset(request.options.size, request.count))
        }

    /**
     * A random legal payment of a summed selection (CR 601.2b, CR 701.60a): the options in a uniformly
     * random order, taken until their weights reach the threshold. Terminates because the request is
     * surfaced only when the whole option list can pay it.
     */
    private fun summedPayment(request: DecisionRequest.SummedSelection): List<Int> {
        val order = permutation(request.optionCount)
        var total = 0
        return order.takeWhile { index ->
            val short = total < request.requiredTotal
            total += request.optionWeights[index]
            short
        }
    }

    /** A uniformly random legal answer size for a ranged selection (CR 601.2c). */
    private fun rangedSize(request: DecisionRequest.RangedSelection): Int {
        val (offset, next) = rng.nextInt(request.maximumCount - request.minimumCount + 1)
        rng = next
        return request.minimumCount + offset
    }

    private fun single(
        id: DecisionRequestId,
        optionCount: Int,
    ): Decision.SingleSelect {
        val (index, next) = rng.nextInt(optionCount)
        rng = next
        return Decision.SingleSelect(id, index)
    }

    private fun multi(
        id: DecisionRequestId,
        indices: List<Int>,
    ): Decision.MultiSelect = Decision.MultiSelect(id, indices)

    /** [count] distinct indices from `0 until size`, via a partial Fisher-Yates over the frozen shuffle. */
    private fun subset(
        size: Int,
        count: Int,
    ): List<Int> {
        val (shuffled, next) = (0 until size).toList().toPersistentList().shuffled(rng)
        rng = next
        return shuffled.take(count)
    }

    /** A uniformly random permutation of `0 until size` (blocker/trigger order). */
    private fun permutation(size: Int): List<Int> {
        val (order, next) = (0 until size).toList().toPersistentList().shuffled(rng)
        rng = next
        return order
    }

    /** Each index in `0 until size` included on an independent fair coin flip (attacker subset). */
    private fun anySizeSubset(size: Int): List<Int> =
        (0 until size).filter {
            val (bit, next) = rng.nextInt(2)
            rng = next
            bit == 0
        }

    /** A legal block assignment: each blocker blocks nothing or one attacker it may block (CR 509.1a). */
    private fun blockAssignment(options: List<DecisionRequest.DeclareBlockers.Option>): List<Int> {
        val byBlocker = LinkedHashMap<ObjectId, MutableList<Int>>()
        options.forEachIndexed { index, option ->
            byBlocker.getOrPut(option.blocker) { mutableListOf() }.add(index)
        }
        val chosen = mutableListOf<Int>()
        for (blockerOptions in byBlocker.values) {
            val (pick, next) = rng.nextInt(blockerOptions.size + 1)
            rng = next
            if (pick < blockerOptions.size) chosen.add(blockerOptions[pick])
        }
        return chosen
    }
}
