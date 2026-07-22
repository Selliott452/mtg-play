package dev.mtgplay.acceptance.fuzz

import dev.mtgplay.rules.decision.Decision
import dev.mtgplay.rules.decision.DecisionRequest

/**
 * The loud failure raised when an enumeration-completeness probe makes the engine throw
 * (deliverable 2 of P3.3): an option the engine [enumerated][DecisionRequest] as legal (ADR-005)
 * turned out not to be playable from the paused state — a phantom option.
 *
 * It carries the full probe context so the failure is self-diagnosing and can be persisted into a
 * repro: the [request] whose enumeration was wrong, the [optionLabel] naming the offending option,
 * the [probedDecision] that reproduced it, and the underlying engine throwable as the [cause].
 *
 * @property request the decision request whose enumerated options were probed.
 * @property optionLabel the human-readable tag of the option that failed
 *   ([EnumerationProbe.ProbeCandidate.label]).
 * @property probedDecision the decision that selected the offending option.
 */
class ProbeFailure(
    val request: DecisionRequest,
    val optionLabel: String,
    val probedDecision: Decision,
    cause: Throwable,
) : IllegalStateException(describe(request, optionLabel, cause), cause) {
    private companion object {
        fun describe(
            request: DecisionRequest,
            optionLabel: String,
            cause: Throwable,
        ): String =
            "ADR-005 enumeration-completeness probe failed: option [$optionLabel] of " +
                "${request::class.simpleName} (seat ${request.seat.seat}) was enumerated as legal but " +
                "advancing it threw ${cause::class.simpleName}: ${cause.message}"
    }
}
