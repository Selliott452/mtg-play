package dev.mtgplay.rules.engine

import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest

/*
 * Decision-shape validation (ADR-004): every incoming decision is checked against the pending request
 * before it is applied, so misuse — a wrong shape, an out-of-range index, a wrong-arity multi-select —
 * never silently corrupts replay (ADR-006). Split from PendingDecision.kt to keep each file small.
 */

internal fun validateDecision(
    request: DecisionRequest,
    decision: Decision,
) {
    require(decision.requestId == request.id) {
        "decision answers request ${decision.requestId}, but the pending request is ${request.id}"
    }
    when (request) {
        is DecisionRequest.ChooseAction -> validateSingleSelect(request, decision, request.options.size)
        // CR 601.2c / 601.2g / 702.19e / 614.12 / 616.1 / 701.17a: every "pick exactly one of these
        // options" request validates identically — one in-range index, and no opt-out index beyond them.
        is DecisionRequest.SingleOptionSelection -> validateSingleSelect(request, decision, request.optionCount)
        // CR 514.1 / 601.2b/h / 602.2b: every fixed-size subset selection validates identically — a
        // distinct in-range subset of exactly the required size.
        is DecisionRequest.SizedSelection -> {
            validateDistinctSubset(request, decision, request.optionCount, "selection")
            val chosen = decision.asMultiSelect(request).indices.size
            require(chosen == request.requiredCount) {
                "exactly ${request.requiredCount} option(s) must be chosen, got $chosen"
            }
        }
        // CR 601.2c: a ranged target selection validates as a distinct in-range subset whose size lies
        // within the request's bounds. The distinctness is not incidental — it *is* "the same target
        // can't be chosen multiple times for any one instance of the word 'target'", which holds
        // because `legalTargets` never offers one object twice.
        is DecisionRequest.RangedSelection -> {
            validateDistinctSubset(request, decision, request.optionCount, "target")
            val chosen = decision.asMultiSelect(request).indices.size
            require(chosen in request.minimumCount..request.maximumCount) {
                "CR 601.2c: between ${request.minimumCount} and ${request.maximumCount} target(s) " +
                    "must be chosen, got $chosen"
            }
        }
        // CR 601.2b/701.60a: a summed selection validates as a distinct in-range subset whose chosen
        // weights *sum* to at least the request's threshold. The size is deliberately unconstrained at
        // both ends — six one-drops and one six-drop both pay collect evidence 6 — so the sum is the
        // whole of the rule, and an under-paying answer is rejected here rather than silently rounded up.
        is DecisionRequest.SummedSelection -> {
            validateDistinctSubset(request, decision, request.optionCount, "selection")
            val total = decision.asMultiSelect(request).indices.sumOf { request.optionWeights[it] }
            require(total >= request.requiredTotal) {
                "CR 701.60a: the chosen options total $total, but at least ${request.requiredTotal} is required"
            }
        }
        is DecisionRequest.DeclareAttackers -> validateAttackerDeclaration(request, decision)
        is DecisionRequest.DeclareBlockers -> validateBlockerDeclaration(request, decision)
        // CR 509.2 / 603.3b: an ordering answer permutes all of its options (blocker order, trigger order).
        is DecisionRequest.PermutationSelection ->
            validatePermutation(request, decision, request.permutationSize, "option", "CR 509.2/603.3b")
        // CR 702.35b: a yes/no is a single-select of exactly two options — decline (0) or accept (1).
        is DecisionRequest.ChooseYesNo ->
            validateSingleSelect(request, decision, DecisionRequest.ChooseYesNo.OPTION_COUNT)
        // A "choose one, or opt out" single-select (CR 701.16 keep-one, CR 601.3b cost-mode, CR 701.18
        // find-one) is validated over all its indices — the real options plus the one opt-out.
        is DecisionRequest.ChoiceCountSelection -> validateSingleSelect(request, decision, request.choiceCount)
        // CR 103.4/103.5: the pre-game mulligan decisions share a validator (Mulligans.kt).
        is DecisionRequest.MulliganRequest -> validateMulliganDecision(request, decision)
    }
}

/**
 * Validates a declare-attackers answer (CR 508.1): a distinct subset of the eligible attackers — the
 * empty one included — that includes every published attack requirement (CR 508.1d).
 *
 * The requirement is checked **here** rather than in the option list because it is not a property of
 * any one option: a goaded creature's option is as legal as any other (CR 701.38a changes nothing
 * about whether it *may* attack), and what goad makes illegal is a declaration that leaves it out.
 * That is a property of the set, exactly as Troll of Khazad-dûm's blocker floor is, and it is checked
 * against the floors the request published so the seat was shown the rule it is being held to.
 */
private fun validateAttackerDeclaration(
    request: DecisionRequest.DeclareAttackers,
    decision: Decision,
) {
    validateDistinctSubset(request, decision, request.options.size, "attacker")
    val declared = decision.asMultiSelect(request).indices.map { request.options[it].attacker }.toSet()
    request.required.forEach { requirement ->
        require(requirement.attacker in declared) {
            "CR 508.1d/701.38a: ${requirement.card.name} was goaded by ${requirement.goadedBy} and " +
                "attacks each combat if able, so this declaration must include it"
        }
    }
}

/**
 * Validates a declare-blockers answer (CR 509.1): a distinct subset, no blocker chosen twice
 * (CR 509.1a), and every published blocker-count floor respected (CR 509.1b).
 *
 * The floor is checked **here** rather than in the option list because it is not a property of any one
 * (blocker, attacker) pairing: Troll of Khazad-dûm's "can't be blocked except by three or more
 * creatures" makes each of the three blocks legal only in the company of the other two, so the legality
 * only exists for the set. Zero blockers always satisfies it — CR 509.1b restricts how a creature may be
 * blocked, never whether it must be.
 */
private fun validateBlockerDeclaration(
    request: DecisionRequest.DeclareBlockers,
    decision: Decision,
) {
    validateDistinctSubset(request, decision, request.options.size, "block")
    val chosen = decision.asMultiSelect(request).indices.map { request.options[it] }
    val blockers = chosen.map { it.blocker }
    require(blockers.distinct().size == blockers.size) {
        "CR 509.1a: a creature blocks at most one attacker, but a blocker was chosen twice: $blockers"
    }
    request.minimumBlockers.forEach { floor ->
        val count = chosen.count { it.attacker == floor.attacker }
        require(count == 0 || count >= floor.minimum) {
            "CR 509.1b: ${floor.attackerCard.name} can't be blocked except by ${floor.minimum} or " +
                "more creatures, but $count was declared"
        }
    }
}

/**
 * Validates a multi-select answer as a permutation of all [optionCount] options — a full ordering with
 * the correct arity, no repeats, and every index in range (CR 509.2 blocker order, CR 603.3b trigger
 * order). [noun] and [cr] name the option kind and rule in the failure messages.
 */
private fun validatePermutation(
    request: DecisionRequest,
    decision: Decision,
    optionCount: Int,
    noun: String,
    cr: String,
) {
    require(decision is Decision.MultiSelect) {
        "a ${request::class.simpleName} request requires a MultiSelect decision, got ${decision::class.simpleName}"
    }
    require(decision.indices.size == optionCount) {
        "$cr: the order must permute all $optionCount ${noun}s, got ${decision.indices.size}"
    }
    require(decision.indices.distinct().size == decision.indices.size) {
        "$cr: a $noun order has no repeats, got ${decision.indices}"
    }
    require(decision.indices.all { it in 0 until optionCount }) {
        "$cr: order indices ${decision.indices} out of range for $optionCount $noun(s)"
    }
}

/**
 * Validates a multi-select answer as a distinct, in-range subset of [optionCount] options — of
 * any size, including empty (CR 508.1 / CR 509.1 both permit declaring nothing). [noun] names the
 * option kind in the failure message.
 */
internal fun validateDistinctSubset(
    request: DecisionRequest,
    decision: Decision,
    optionCount: Int,
    noun: String,
) {
    require(decision is Decision.MultiSelect) {
        "a ${request::class.simpleName} request requires a MultiSelect decision, got ${decision::class.simpleName}"
    }
    require(decision.indices.distinct().size == decision.indices.size) {
        "$noun indices must be distinct, got ${decision.indices}"
    }
    require(decision.indices.all { it in 0 until optionCount }) {
        "$noun indices ${decision.indices} out of range for $optionCount option(s)"
    }
}

// The decision as a MultiSelect; only called after validateDistinctSubset has proven the shape.
internal fun Decision.asMultiSelect(request: DecisionRequest): Decision.MultiSelect =
    this as? Decision.MultiSelect
        ?: error("unreachable: ${request::class.simpleName} decision shape was validated to MultiSelect")

internal fun validateSingleSelect(
    request: DecisionRequest,
    decision: Decision,
    optionCount: Int,
) {
    require(decision is Decision.SingleSelect) {
        "a ${request::class.simpleName} request requires a SingleSelect decision, got ${decision::class.simpleName}"
    }
    require(decision.index in 0 until optionCount) {
        "option index ${decision.index} is out of range for $optionCount option(s)"
    }
}
