package dev.mtgplay.server.client

import dev.mtgplay.core.random.Rng
import dev.mtgplay.protocol.BlockerOptionDto
import dev.mtgplay.protocol.DecisionDto
import dev.mtgplay.protocol.DecisionRequestDto
import dev.mtgplay.protocol.DecisionRequestIdDto

/**
 * A uniformly-random *legal* [RemoteAgent] that reads only the wire schema (`mtg-protocol`) — it answers
 * a [DecisionRequestDto] with a [DecisionDto] and never touches the engine (no `mtg-rules` decision
 * types, no `GameState`, no `viewFor`). This is what "the wire contract suffices" means (ADR-008): a
 * client can play a whole real game knowing only the DTOs. The single non-schema import is `mtg-core`'s
 * [Rng] — the sanctioned seeded generator (ADR-006), core vocabulary rather than an engine import.
 *
 * Every option is engine-enumerated (ADR-005), so "random legal" is a uniform pick over indices —
 * exactly the DTO mirror of the engine-side `RandomLegalResponder` in `mtg-acceptance`. All randomness
 * flows through the seeded core [Rng] (the detekt ban forbids `kotlin.random`, and a deterministic
 * client must use the sanctioned generator), so a `(seed)` reproduces a client's choices exactly.
 *
 * @param seed the seed for this agent's decision randomness.
 */
class RandomRemoteAgent(
    seed: Long,
) : RemoteAgent {
    private var rng: Rng = Rng(seed)

    /** Picks a legal [DecisionDto] for [request]. Dispatch is grouped by the DTO's six families to stay flat. */
    override fun decide(request: DecisionRequestDto): DecisionDto =
        when (request) {
            is DecisionRequestDto.SizedSelectionDto -> sizedSelection(request)
            // A ranged subset selection (CR 601.2c) — a multi-target choice: a random distinct subset
            // whose size is drawn uniformly from the request's own bounds. Distinct indices are
            // CR 601.2c's rule that one object cannot be chosen twice for one instance of "target".
            is DecisionRequestDto.RangedSelectionDto -> {
                val (optionCount, minimum, maximum) = rangedBounds(request)
                val (offset, next) = rng.nextInt(maximum - minimum + 1)
                rng = next
                DecisionDto.MultiSelect(request.id, subset(optionCount, minimum + offset))
            }
            is DecisionRequestDto.PermutationSelectionDto -> permutationSelection(request)
            is DecisionRequestDto.ChoiceCountSelectionDto -> singleSelect(request.id, choiceCount(request))
            is DecisionRequestDto.MulliganRequestDto -> mulligan(request)
            is DecisionRequestDto.ChooseAction -> singleSelect(request.id, request.options.size)
            // Every "pick exactly one of these options" request is a uniform pick over its own options.
            is DecisionRequestDto.SingleOptionSelectionDto -> singleSelect(request.id, singleOptionCount(request))
            is DecisionRequestDto.ChooseYesNo -> singleSelect(request.id, YES_NO_OPTION_COUNT)
            is DecisionRequestDto.DeclareAttackers -> anySubset(request.id, request.options.size)
            is DecisionRequestDto.DeclareBlockers -> blockAssignment(request.id, request.options)
        }

    // A fixed-size subset selection (CR 514.1 / 601.2b/h / 602.2b): a random correct-size subset.
    private fun sizedSelection(request: DecisionRequestDto.SizedSelectionDto): DecisionDto {
        val (optionCount, requiredCount) =
            when (request) {
                is DecisionRequestDto.ChooseDiscards -> request.options.size to request.count
                is DecisionRequestDto.ChooseCardsToExile -> request.options.size to request.count
                is DecisionRequestDto.ChooseSacrifices -> request.options.size to request.count
                is DecisionRequestDto.ChooseCardsToDiscardForCost -> request.options.size to request.count
                is DecisionRequestDto.ChooseSacrificesForCost -> request.options.size to request.count
                is DecisionRequestDto.ChooseAbilitySacrifice -> request.options.size to request.count
                is DecisionRequestDto.ChooseAbilityDiscard -> request.options.size to request.count
                is DecisionRequestDto.ChooseOptionalDiscard -> request.options.size to request.count
                is DecisionRequestDto.ChooseResolutionDiscards -> request.options.size to request.count
                is DecisionRequestDto.ChooseOptionalCostObject -> request.options.size to 1
                // CR 701.7a: an each-opponent discard, answered by an opponent over their own hand.
                is DecisionRequestDto.ChooseOpponentDiscards -> request.options.size to request.count
            }
        return DecisionDto.MultiSelect(request.id, subset(optionCount, requiredCount))
    }

    // A full ordering (CR 509.2 / 603.3b): a uniformly random permutation of all options.
    private fun permutationSelection(request: DecisionRequestDto.PermutationSelectionDto): DecisionDto {
        val size =
            when (request) {
                is DecisionRequestDto.OrderBlockers -> request.options.size
                is DecisionRequestDto.OrderTriggers -> request.options.size
            }
        return DecisionDto.MultiSelect(request.id, subset(size, size))
    }

    // The number of options a "pick exactly one of these" request offers (no opt-out index).
    private fun singleOptionCount(request: DecisionRequestDto.SingleOptionSelectionDto): Int =
        when (request) {
            is DecisionRequestDto.ChooseModes -> request.options.size
            is DecisionRequestDto.ChooseTargets -> request.options.size
            is DecisionRequestDto.ChoosePaymentPlan -> request.options.size
            // CR 601.2b: one index per announceable value of X, not per unit of X.
            is DecisionRequestDto.ChooseXValue -> request.values.size
            is DecisionRequestDto.AssignTrampleDamage -> request.options.size
            is DecisionRequestDto.ChooseColor -> request.options.size
            is DecisionRequestDto.ChooseReplacement -> request.options.size
            is DecisionRequestDto.ChooseLibraryArrangement -> request.options.size
            is DecisionRequestDto.ChooseCounterPayment -> request.options.size
            // CR 701.16a: the controller's pick from an opponent's revealed hand.
            is DecisionRequestDto.ChooseRevealedHandCard -> request.options.size
        }

    // The number of selectable indices of a "choose one, or opt out" request (real options + one opt-out).
    private fun choiceCount(request: DecisionRequestDto.ChoiceCountSelectionDto): Int =
        when (request) {
            is DecisionRequestDto.ChooseFromRevealed -> request.options.size + 1
            is DecisionRequestDto.ChooseCostMode -> request.options.size + 1
            is DecisionRequestDto.ChooseFromLibrary -> request.options.size + 1
        }

    private fun mulligan(request: DecisionRequestDto.MulliganRequestDto): DecisionDto =
        when (request) {
            is DecisionRequestDto.ChooseMulligan -> {
                // Bias toward keeping so playouts terminate fast, while still exercising the mulligan path.
                // Wire indices: 0 keeps the hand, 1 takes a mulligan (CR 103.4).
                val (roll, next) = rng.nextInt(MULLIGAN_DENOMINATOR)
                rng = next
                DecisionDto.SingleSelect(request.id, if (roll == 0) MULLIGAN_INDEX else KEEP_INDEX)
            }
            is DecisionRequestDto.ChooseCardsToBottom ->
                DecisionDto.MultiSelect(request.id, subset(request.options.size, request.count))
        }

    private fun singleSelect(
        id: DecisionRequestIdDto,
        optionCount: Int,
    ): DecisionDto.SingleSelect {
        val (index, next) = rng.nextInt(optionCount)
        rng = next
        return DecisionDto.SingleSelect(id, index)
    }

    // Each index included on an independent fair coin (CR 508.1: any subset of eligible attackers is legal).
    private fun anySubset(
        id: DecisionRequestIdDto,
        size: Int,
    ): DecisionDto.MultiSelect {
        val chosen = mutableListOf<Int>()
        for (index in 0 until size) {
            val (bit, next) = rng.nextInt(2)
            rng = next
            if (bit == 0) chosen += index
        }
        return DecisionDto.MultiSelect(id, chosen)
    }

    // CR 509.1a: each blocker blocks nothing or exactly one attacker it may legally block.
    private fun blockAssignment(
        id: DecisionRequestIdDto,
        options: List<BlockerOptionDto>,
    ): DecisionDto.MultiSelect {
        val byBlocker = LinkedHashMap<Long, MutableList<Int>>()
        options.forEachIndexed { index, option ->
            byBlocker.getOrPut(option.blocker) { mutableListOf() }.add(index)
        }
        val chosen = mutableListOf<Int>()
        for (blockerOptions in byBlocker.values) {
            val (pick, next) = rng.nextInt(blockerOptions.size + 1)
            rng = next
            if (pick < blockerOptions.size) chosen += blockerOptions[pick]
        }
        return DecisionDto.MultiSelect(id, chosen)
    }

    // A random size-[count] subset of [0, size) via a partial Fisher-Yates through the seeded generator.
    private fun subset(
        size: Int,
        count: Int,
    ): List<Int> {
        val pool = (0 until size).toMutableList()
        val chosen = ArrayList<Int>(count)
        repeat(count) {
            val (pick, next) = rng.nextInt(pool.size)
            rng = next
            chosen += pool.removeAt(pick)
        }
        return chosen
    }

    private companion object {
        const val YES_NO_OPTION_COUNT: Int = 2
        const val MULLIGAN_DENOMINATOR: Int = 8
        const val KEEP_INDEX: Int = 0
        const val MULLIGAN_INDEX: Int = 1
    }
}

/** The option count and inclusive answer-size bounds of a ranged selection (CR 601.2c). */
private fun rangedBounds(request: DecisionRequestDto.RangedSelectionDto): Triple<Int, Int, Int> =
    when (request) {
        is DecisionRequestDto.ChooseMultipleTargets ->
            Triple(request.options.size, request.minimumCount, request.maximumCount)
    }
