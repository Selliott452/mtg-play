package dev.mtgplay.acceptance.invariant

/**
 * One detected invariant violation: which [Invariant] failed, and a human-readable [detail]
 * pinpointing how (which object, which seat, which counts).
 *
 * A violation is a value, not an exception: [InvariantChecker.check] returns every violation it
 * finds so a caller can report them all at once. The scripted-game driver is what turns a
 * non-empty violation list into a loud failure after each transition.
 *
 * @property invariant the invariant that failed.
 * @property detail a diagnostic message naming the offending objects/seats/counts; part of the
 *   failure output, so it must be specific enough to start debugging from.
 */
data class Violation(
    val invariant: Invariant,
    val detail: String,
)
