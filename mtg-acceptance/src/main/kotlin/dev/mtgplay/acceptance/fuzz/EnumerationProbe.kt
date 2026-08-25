package dev.mtgplay.acceptance.fuzz

import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.GameEngine
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest
import dev.mtgplay.rules.decision.DecisionRequestId

/**
 * The enumeration-completeness probe (deliverable 2 of P3.3): turns ADR-005's "no phantom options"
 * from a one-off unit-tested property into a property continuously fuzzed inside every random
 * playout.
 *
 * At a paused decision window the engine has enumerated exactly the legal options (ADR-005). The
 * probe takes each enumerated option, constructs a decision that selects it, and asks the engine to
 * [advance][GameEngine.advance] the **immutable** paused state with it. Because `advance` is pure
 * and never mutates its input (ADR-004), every option is probed against the very same state and the
 * results are discarded — the real playout then proceeds with whichever option the responder chose.
 * A genuinely legal option advances cleanly; a phantom option (enumerated but not actually playable)
 * makes `advance` throw, which the probe re-raises as a [ProbeFailure] carrying the offending option
 * so the failure points straight at the enumeration bug (PLAN.md §7: fail loudly, never approximate).
 *
 * **How "each enumerated option" maps to a decision.** Single-select requests probe one decision per
 * option index — including a library arrangement (CR 701.17a), whose whole option list *is* the legality
 * rule (ADR-005), so probing it is the guard against an incomplete or phantom enumeration.
 * Multi-select requests probe the smallest legal decision that exercises each option:
 * a discard probes a correctly-sized selection that includes each card in turn; attacker and blocker
 * declarations probe each option as a singleton (any single legal attacker/block is legal on its
 * own) plus the empty declaration (declaring nothing is always legal, CR 508.8 / 509.1); a
 * blocker-ordering request has no per-option decision — every valid answer is a permutation of *all*
 * its options at once — so it probes one representative valid permutation, which exercises every
 * option simultaneously.
 */
object EnumerationProbe {
    /**
     * One decision the probe will try against the paused state, tagged with a [label] naming the
     * enumerated option it exercises (for the failure message and the persisted repro).
     */
    data class ProbeCandidate(
        val label: String,
        val decision: Decision,
    )

    /**
     * Probes every enumerated option of [request] against the immutable [state] via [engine],
     * discarding each result. Returns the number of options probed. Throws [ProbeFailure] if any
     * probe makes the engine throw — the enumeration offered an option that is not actually
     * playable from [state].
     */
    fun probe(
        engine: GameEngine,
        state: GameState,
        request: DecisionRequest,
    ): Int {
        val candidates = candidatesFor(request)
        candidates.forEach { candidate -> probeOne(engine, state, request, candidate) }
        return candidates.size
    }

    /**
     * Advances [state] with one probe [candidate] and discards the result — `advance` is pure
     * (ADR-004), so probing leaves the real playout untouched; only that it did not throw matters.
     * A throw means the option was a phantom, re-raised as a [ProbeFailure].
     *
     * The three caught types are exactly the engine's loud-failure idioms (CONVENTIONS.md): a
     * rejected decision or unsupported corner (`error(...)`/`require(...)` — [IllegalStateException],
     * [IllegalArgumentException]) and an unimplemented corner (`TODO()` — [NotImplementedError]).
     */
    private fun probeOne(
        engine: GameEngine,
        state: GameState,
        request: DecisionRequest,
        candidate: ProbeCandidate,
    ) {
        val failure: Throwable? =
            try {
                engine.advance(state, candidate.decision)
                null
            } catch (failure: IllegalStateException) {
                failure
            } catch (failure: IllegalArgumentException) {
                failure
            } catch (failure: NotImplementedError) {
                failure
            }
        if (failure != null) throw ProbeFailure(request, candidate.label, candidate.decision, failure)
    }

    /**
     * The probe candidates for [request]: one representative legal decision per enumerated option,
     * per the mapping documented on [EnumerationProbe]. Exhaustive over the [DecisionRequest]
     * hierarchy, so a new request kind breaks compilation here rather than silently going unprobed.
     */
    internal fun candidatesFor(request: DecisionRequest): List<ProbeCandidate> =
        when (request) {
            is DecisionRequest.ChooseAction ->
                request.options.mapIndexed { index, option ->
                    ProbeCandidate("action[$index]=$option", Decision.SingleSelect(request.id, index))
                }
            is DecisionRequest.ChooseTargets ->
                request.options.mapIndexed { index, target ->
                    ProbeCandidate("target[$index]=$target", Decision.SingleSelect(request.id, index))
                }
            // Every other "pick exactly one of these options" request — a payment plan (CR 601.2g), a
            // trample assignment (CR 702.19e), an as-enters colour (CR 614.12), a replacement ordering
            // (CR 616.1), a library arrangement (CR 701.17a) — has independently legal options, so each
            // index is probed: legality *is* the enumeration (ADR-005).
            is DecisionRequest.SingleOptionSelection ->
                singleSelectPerOption(request.id, request.optionCount, "option")
            // Any fixed-size subset selection (CR 514.1 / 601.2b/h / 602.2b): a correctly-sized selection
            // that includes each option in turn (discards, exile, sacrifice, ability discard).
            is DecisionRequest.SizedSelection ->
                sizedSelectionIncludingEach(request.id, request.optionCount, request.requiredCount, "sized-including")
            // CR 601.2c: a multi-target choice. Every distinct subset within the request's bounds is
            // legal, and the probe covers the three edges that actually break: the smallest allowed
            // selection, the largest, and each option carried inside a smallest-viable one.
            is DecisionRequest.RangedSelection -> rangedSelectionCandidates(request)
            is DecisionRequest.DeclareAttackers ->
                // Declaring nothing is always legal (CR 508.8); each eligible attacker is legal as a
                // singleton (any subset is a legal declaration, CR 508.1a).
                declarationCandidates(request.id, request.options.size, "attack")
            is DecisionRequest.DeclareBlockers ->
                // Blocking nothing is always legal (CR 509.1); each (blocker, attacker) pairing is
                // legal as a singleton — the only cross-option rule is that no blocker is used twice
                // (CR 509.1a), which a singleton cannot violate.
                declarationCandidates(request.id, request.options.size, "block")
            is DecisionRequest.PermutationSelection ->
                // A blocker/trigger order is a permutation of *all* the options (CR 509.2 / 603.3b); the
                // identity permutation is one representative valid answer exercising every option at once.
                listOf(
                    ProbeCandidate(
                        "order-identity",
                        Decision.MultiSelect(request.id, (0 until request.permutationSize).toList()),
                    ),
                )
            is DecisionRequest.ChooseYesNo ->
                // Both the decline (0) and the accept (1) of a "you may" are legal (CR 702.35b): the
                // request is surfaced only when accepting is playable, so both are probed.
                listOf(
                    ProbeCandidate(
                        "yesno-decline",
                        Decision.SingleSelect(request.id, DecisionRequest.ChooseYesNo.DECLINE),
                    ),
                    ProbeCandidate(
                        "yesno-accept",
                        Decision.SingleSelect(request.id, DecisionRequest.ChooseYesNo.ACCEPT),
                    ),
                )
            // Each index of a "choose one, or opt out" choice (CR 701.16 keep-one, CR 601.3b cost-mode,
            // CR 701.18 find-one) — the real options plus the opt-out — is independently legal.
            is DecisionRequest.ChoiceCountSelection ->
                singleSelectPerOption(request.id, request.choiceCount, "choice")
            // CR 103.4/103.5: both keep and mulligan are always legal; each bottom card is probed
            // inside a correctly-sized selection.
            is DecisionRequest.MulliganRequest -> mulliganCandidates(request)
        }

    /**
     * The probe candidates for a combat declaration (CR 508.1 / CR 509.1): the empty declaration, which is
     * always legal, plus each option as a singleton, labelled `[noun]`.
     */
    private fun declarationCandidates(
        id: DecisionRequestId,
        optionCount: Int,
        noun: String,
    ): List<ProbeCandidate> =
        listOf(ProbeCandidate("$noun-none", Decision.MultiSelect(id, emptyList()))) +
            (0 until optionCount).map { index ->
                ProbeCandidate("$noun-only[$index]", Decision.MultiSelect(id, listOf(index)))
            }

    /** The probe candidates for a pre-game mulligan decision (CR 103.4/103.5). */
    private fun mulliganCandidates(request: DecisionRequest.MulliganRequest): List<ProbeCandidate> =
        when (request) {
            is DecisionRequest.ChooseMulligan ->
                listOf(
                    ProbeCandidate(
                        "mulligan-keep",
                        Decision.SingleSelect(request.id, DecisionRequest.ChooseMulligan.KEEP),
                    ),
                    ProbeCandidate(
                        "mulligan-take",
                        Decision.SingleSelect(request.id, DecisionRequest.ChooseMulligan.MULLIGAN),
                    ),
                )
            is DecisionRequest.ChooseCardsToBottom ->
                sizedSelectionIncludingEach(request.id, request.options.size, request.count, "bottom-including")
        }

    /**
     * The probe candidates for a ranged subset selection (CR 601.2c): the minimum-size selection, the
     * maximum-size one, and one minimum-viable selection carrying each option in turn. Every candidate
     * has distinct indices, which is CR 601.2c's same-object rule — a probe that offered a repeated
     * index would be asserting the engine accepts an illegal combination, which is the one thing a
     * multi-target enumeration must never do.
     */
    private fun rangedSelectionCandidates(request: DecisionRequest.RangedSelection): List<ProbeCandidate> {
        val id = request.id
        val carrying = maxOf(request.minimumCount, 1)
        val includingEach =
            (0 until request.optionCount).map { index ->
                val selection = (listOf(index) + (0 until request.optionCount).filter { it != index }).take(carrying)
                ProbeCandidate("target-including[$index]", Decision.MultiSelect(id, selection))
            }
        val edges =
            listOf(request.minimumCount, request.maximumCount).distinct().map { size ->
                ProbeCandidate("target-size[$size]", Decision.MultiSelect(id, (0 until size).toList()))
            }
        return edges + includingEach
    }

    /** One single-select probe per option index, labelled `[prefix][i]`. */
    private fun singleSelectPerOption(
        id: DecisionRequestId,
        optionCount: Int,
        prefix: String,
    ): List<ProbeCandidate> =
        (0 until optionCount).map { index ->
            ProbeCandidate("$prefix[$index]", Decision.SingleSelect(id, index))
        }

    /**
     * One multi-select probe per option index, each a correctly-sized selection that includes that
     * index (the index itself, then the lowest-indexed others until exactly [count] are chosen).
     */
    private fun sizedSelectionIncludingEach(
        id: DecisionRequestId,
        optionCount: Int,
        count: Int,
        prefix: String,
    ): List<ProbeCandidate> =
        (0 until optionCount).map { index ->
            val selection = (listOf(index) + (0 until optionCount).filter { it != index }).take(count)
            ProbeCandidate("$prefix[$index]", Decision.MultiSelect(id, selection))
        }
}
