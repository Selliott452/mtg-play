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
            // A summed-weight selection (CR 601.2b, CR 701.60a) — collect evidence: options taken in a
            // random order until their weights reach the threshold. This is the one family a client
            // cannot answer from the index range alone, which is why the weights are on the wire.
            is DecisionRequestDto.SummedSelectionDto -> summedSelection(request)
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
                is DecisionRequestDto.ChooseTapsForCost -> request.options.size to request.count
                is DecisionRequestDto.ChooseOptionalCostSacrifice -> request.options.size to request.count
                // CR 601.2b (`W9-D`): exactly one power source is named, always.
                is DecisionRequestDto.ChooseCostPowerSource -> request.options.size to 1
                is DecisionRequestDto.ChooseCardsToDiscardForCost -> request.options.size to request.count
                is DecisionRequestDto.ChooseSacrificesForCost -> request.options.size to request.count
                else -> abilityOrResolutionBounds(request)
            }
        return DecisionDto.MultiSelect(request.id, subset(optionCount, requiredCount))
    }

    /**
     * A summed-weight subset selection (CR 601.2b, CR 701.60a): the options in a uniformly random order,
     * taken until their weights reach the request's threshold. Terminates because the request is
     * surfaced only when the whole option list can reach it (ADR-005).
     */
    private fun summedSelection(request: DecisionRequestDto.SummedSelectionDto): DecisionDto {
        val (weights, requiredTotal) =
            when (request) {
                is DecisionRequestDto.ChooseEvidence -> request.options.map { it.weight } to request.requiredTotal
            }
        val order = subset(weights.size, weights.size)
        var total = 0
        val chosen =
            order.takeWhile { index ->
                val short = total < requiredTotal
                total += weights[index]
                short
            }
        return DecisionDto.MultiSelect(request.id, chosen)
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
            else -> resolutionClauseOptionCount(request)
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

/**
 * The number of selectable indices of a "choose one, or opt out" request (real options + one opt-out).
 *
 * A top-level function rather than a member, like [rangedBounds] below it, because it needs no
 * randomness at all — and because `W9-B`'s summed-selection arm pushed the class itself past detekt's
 * member budget, which is a real signal that the pure request-shape readers belong outside it.
 */
private fun choiceCount(request: DecisionRequestDto.ChoiceCountSelectionDto): Int =
    when (request) {
        is DecisionRequestDto.ChooseFromRevealed -> request.options.size + 1
        is DecisionRequestDto.ChooseCostMode -> request.options.size + 1
        is DecisionRequestDto.ChooseFromLibrary -> request.options.size + 1
    }

/** The option count and inclusive answer-size bounds of a ranged selection (CR 601.2c, CR 609.4). */
private fun rangedBounds(request: DecisionRequestDto.RangedSelectionDto): Triple<Int, Int, Int> =
    when (request) {
        // CR 601.2b: a mode choice is ranged too — "choose one" is the `1..1` case (`W9-B`).
        is DecisionRequestDto.ChooseModes ->
            Triple(request.options.size, request.minimumCount, request.maximumCount)
        is DecisionRequestDto.ChooseMultipleTargets ->
            Triple(request.options.size, request.minimumCount, request.maximumCount)
        is DecisionRequestDto.ChoosePermanentsToAffect ->
            Triple(request.options.size, request.minimumCount, request.maximumCount)
    }

// The tail of [singleOptionCount]: the clauses a resolving object opens. Split out only so the
// dispatch stays inside detekt's complexity budget — the same shape as the splits this wave forced
// in `PendingDecision.kt`, `DecisionView.kt`, `SingleOptionApplication.kt`, the CLI menu family,
// and the protocol codec. Both halves stay exhaustive, so a new member still breaks compilation.
private fun resolutionClauseOptionCount(request: DecisionRequestDto.SingleOptionSelectionDto): Int =
    when (request) {
        // CR 608.2c: decline, tap, or untap a clause's target.
        is DecisionRequestDto.ChooseTapOrUntap -> request.options.size
        // CR 601.3b: decline at index 0, then one option per affordable payment plan.
        is DecisionRequestDto.ChooseOptionalManaPayment -> request.options.size
        // CR 701.3a: the deciding player's own graveyard, one index per card, plus the "exile nothing"
        // index of a "you may exile" (CR 601.3b, Masked Vandal).
        is DecisionRequestDto.ChooseGraveyardCardToExile ->
            request.options.size + if (request.optionalExile) 1 else 0
        // CR 401.1: the two library depths a permanent's owner may name.
        is DecisionRequestDto.ChooseLibraryPosition -> request.options.size
        // CR 701.40a: the two destinations an explorer may name for the revealed card.
        is DecisionRequestDto.ChooseExploreDestination -> request.options.size
        // CR 609.4: one index per offered card type, chosen before anything is revealed.
        is DecisionRequestDto.ChooseRevealedCardType -> request.options.size
        // CR 309.4: one index per room the venturing player's marker may move to.
        is DecisionRequestDto.ChooseDungeonRoom -> request.options.size
        else -> error("no option count for ${request::class.simpleName}")
    }

/**
 * The activation-side and mid-resolution arms of [RandomRemoteAgent]'s sized-selection bounds, split out
 * to keep that `when` inside detekt's complexity budget — and top-level rather than a further method
 * because the class is at its own function budget. The split follows the cast-side / ability-side axis
 * the wire mapping already uses, and this half reads nothing but its argument.
 */
private fun abilityOrResolutionBounds(request: DecisionRequestDto.SizedSelectionDto): Pair<Int, Int> =
    when (request) {
        is DecisionRequestDto.ChooseAbilitySacrifice -> request.options.size to request.count
        is DecisionRequestDto.ChooseAbilityDiscard -> request.options.size to request.count
        is DecisionRequestDto.ChooseAbilityReturn -> request.options.size to request.count
        is DecisionRequestDto.ChooseOptionalDiscard -> request.options.size to request.count
        is DecisionRequestDto.ChooseResolutionDiscards -> request.options.size to request.count
        is DecisionRequestDto.ChooseOptionalCostObject -> request.options.size to 1
        // CR 701.7a: an each-opponent discard, answered by an opponent over their own hand.
        is DecisionRequestDto.ChooseOpponentDiscards -> request.options.size to request.count
        // CR 701.17a: an each-opponent sacrifice, answered by an opponent over their own battlefield.
        is DecisionRequestDto.ChooseOpponentSacrifice -> request.options.size to 1
        // Every cast-side cost is handled by the caller; reaching here would mean a leaf fell out of both
        // `when`s, which the compiler cannot catch once one of them carries an `else`.
        else -> error("no bounds for the sized selection ${request::class.simpleName}")
    }
