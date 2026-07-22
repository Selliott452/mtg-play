package dev.mtgplay.acceptance.fuzz

import dev.mtgplay.core.state.GameState
import dev.mtgplay.rules.GameEngine
import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest

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
 * option index. Multi-select requests probe the smallest legal decision that exercises each option:
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
            is DecisionRequest.ChoosePaymentPlan ->
                request.options.indices.map { index ->
                    ProbeCandidate("payment[$index]", Decision.SingleSelect(request.id, index))
                }
            is DecisionRequest.ChooseDiscards ->
                request.options.indices.map { index ->
                    // A correctly-sized (CR 514.1) discard that includes card `index`: the card
                    // itself, then the lowest-indexed others until exactly `count` are chosen.
                    val selection = (listOf(index) + request.options.indices.filter { it != index }).take(request.count)
                    ProbeCandidate("discard-including[$index]", Decision.MultiSelect(request.id, selection))
                }
            is DecisionRequest.DeclareAttackers ->
                // Declaring nothing is always legal (CR 508.8); each eligible attacker is legal as a
                // singleton (any subset is a legal declaration, CR 508.1a).
                listOf(ProbeCandidate("attack-none", Decision.MultiSelect(request.id, emptyList()))) +
                    request.options.indices.map { index ->
                        ProbeCandidate("attack-only[$index]", Decision.MultiSelect(request.id, listOf(index)))
                    }
            is DecisionRequest.DeclareBlockers ->
                // Blocking nothing is always legal (CR 509.1); each (blocker, attacker) pairing is
                // legal as a singleton — the only cross-option rule is that no blocker is used twice
                // (CR 509.1a), which a singleton cannot violate.
                listOf(ProbeCandidate("block-none", Decision.MultiSelect(request.id, emptyList()))) +
                    request.options.indices.map { index ->
                        ProbeCandidate("block-only[$index]", Decision.MultiSelect(request.id, listOf(index)))
                    }
            is DecisionRequest.OrderBlockers ->
                // A blocker order is a permutation of *all* the options (CR 509.2); the identity
                // permutation is one representative valid answer that exercises every option at once.
                listOf(
                    ProbeCandidate(
                        "order-identity",
                        Decision.MultiSelect(request.id, request.options.indices.toList()),
                    ),
                )
        }
}
