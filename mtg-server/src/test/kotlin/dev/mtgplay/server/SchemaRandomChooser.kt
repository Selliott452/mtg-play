package dev.mtgplay.server

import dev.mtgplay.core.random.Rng
import dev.mtgplay.protocol.DecisionDto
import dev.mtgplay.protocol.DecisionRequestDto
import dev.mtgplay.protocol.DecisionRequestIdDto

/**
 * A uniformly-random *legal* chooser that reads only the wire schema (`mtg-protocol`) — it answers a
 * [DecisionRequestDto] with a [DecisionDto] and never touches the engine (no `mtg-rules` decision
 * types, no `GameState`, no `viewFor`). This is what "the wire contract suffices" means: a client
 * can play a whole real game knowing only the DTOs.
 *
 * Every option is engine-enumerated (ADR-005), so "random legal" is a uniform pick over indices —
 * exactly the DTO mirror of the engine-side `RandomLegalResponder` in `mtg-acceptance`. All
 * randomness flows through the seeded core [Rng] (ADR-006; the detekt ban forbids `kotlin.random`,
 * and a deterministic client must use the sanctioned generator — [Rng] is core vocabulary, not an
 * engine import), so a `(seed)` reproduces a client's choices exactly.
 *
 * @param seed the seed for this chooser's decision randomness.
 */
class SchemaRandomChooser(
    seed: Long,
) {
    private var rng: Rng = Rng(seed)

    /** Picks a legal [DecisionDto] for [request]. Dispatch is grouped by the DTO's four families to stay flat. */
    fun choose(request: DecisionRequestDto): DecisionDto =
        when (request) {
            is DecisionRequestDto.SizedSelectionDto -> sizedSelection(request)
            is DecisionRequestDto.PermutationSelectionDto -> permutationSelection(request)
            is DecisionRequestDto.ChoiceCountSelectionDto -> singleSelect(request.id, choiceCount(request))
            is DecisionRequestDto.MulliganRequestDto -> mulligan(request)
            is DecisionRequestDto.ChooseAction -> singleSelect(request.id, request.options.size)
            is DecisionRequestDto.ChooseTargets -> singleSelect(request.id, request.options.size)
            is DecisionRequestDto.ChoosePaymentPlan -> singleSelect(request.id, request.options.size)
            is DecisionRequestDto.AssignTrampleDamage -> singleSelect(request.id, request.options.size)
            is DecisionRequestDto.ChooseYesNo -> singleSelect(request.id, YES_NO_OPTION_COUNT)
            is DecisionRequestDto.ChooseColor -> singleSelect(request.id, request.options.size)
            is DecisionRequestDto.ChooseReplacement -> singleSelect(request.id, request.options.size)
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
                is DecisionRequestDto.ChooseAbilityDiscard -> request.options.size to request.count
                is DecisionRequestDto.ChooseOptionalDiscard -> request.options.size to request.count
                is DecisionRequestDto.ChooseResolutionDiscards -> request.options.size to request.count
                is DecisionRequestDto.ChooseOptionalCostObject -> request.options.size to 1
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
        options: List<dev.mtgplay.protocol.BlockerOptionDto>,
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
